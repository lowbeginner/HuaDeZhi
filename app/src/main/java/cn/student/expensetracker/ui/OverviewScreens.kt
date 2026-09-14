package cn.student.expensetracker.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.student.expensetracker.data.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

@Composable
internal fun HomeScreen(expenses: List<ExpenseDetails>, categories: List<Category>, today: LocalDate, onOpen: (String) -> Unit, onRecords: () -> Unit) {
    val categoryMap = remember(categories) { categories.associateBy { it.id } }
    val active = expenses.filter { canUseNow(it, today) }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("记下每一笔，让每一次更值得。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("本月消费", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("${today.year} / ${today.monthValue.toString().padStart(2, '0')}", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(totalLabel(inPeriod(expenses, Period.MONTH, today)), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .2f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("今日消费", style = MaterialTheme.typography.labelMedium)
                            Text(totalLabel(inPeriod(expenses, Period.TODAY, today)), style = MaterialTheme.typography.titleMedium)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("本周消费", style = MaterialTheme.typography.labelMedium)
                            Text(totalLabel(inPeriod(expenses, Period.WEEK, today)), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
        item { SectionTitle("最近消费", trailing = { TextButton(onClick = onRecords) { Text("查看全部") } }) }
        if (expenses.isEmpty()) item { EmptyState("从第一笔消费开始", "点击右下角「记一笔」，记录日常开销或长期项目。") }
        items(expenses.sortedWith(compareByDescending<ExpenseDetails> { it.expense.purchaseDate }.thenByDescending { it.expense.createdAt }).take(4), key = { "recent-${it.expense.id}" }) { item ->
            ExpenseRow(item, categoryMap[item.expense.categoryId]?.name ?: "其他", today) { onOpen(item.expense.id) }
        }
        item { Spacer(Modifier.height(4.dp)); SectionTitle("正在使用", "${active.size} 个长期项目 · 每用一次，更新一次成本") }
        if (active.isEmpty()) item { EmptyState("暂无正在使用的项目", "健身卡、会员和月卡都可以记录每次使用。") }
        items(active, key = { "active-${it.expense.id}" }) { item -> ActiveCard(item, today) { onOpen(item.expense.id) } }
    }
}

private enum class RecordSort(val label: String) { NEWEST("日期：从新到旧"), OLDEST("日期：从旧到新"), HIGHEST("金额：从高到低"), LOWEST("金额：从低到高") }

@Composable
internal fun RecordsScreen(expenses: List<ExpenseDetails>, categories: List<Category>, today: LocalDate, onOpen: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("ALL") }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var sortName by rememberSaveable { mutableStateOf(RecordSort.NEWEST.name) }
    var sortMenu by remember { mutableStateOf(false) }
    val sort = RecordSort.valueOf(sortName)
    val categoryMap = remember(categories) { categories.associateBy { it.id } }
    val shown = remember(expenses, query, type, categoryId, sort) {
        val filtered = expenses.filter { (query.isBlank() || it.expense.name.contains(query.trim(), ignoreCase = true)) &&
            (type == "ALL" || it.expense.type == type) && (categoryId == null || categoryId == it.expense.categoryId) }
        when (sort) {
            RecordSort.NEWEST -> filtered.sortedWith(compareByDescending<ExpenseDetails> { it.expense.purchaseDate }.thenByDescending { it.expense.createdAt })
            RecordSort.OLDEST -> filtered.sortedBy { it.expense.purchaseDate }
            RecordSort.HIGHEST -> filtered.sortedByDescending { it.expense.amountCents }
            RecordSort.LOWEST -> filtered.sortedBy { it.expense.amountCents }
        }
    }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("搜索消费名称") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清除搜索") }
                }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("record_search"))
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL" to "全部类型", ExpenseType.SINGLE to "单次消费", ExpenseType.MULTI_USE to "多次消费").forEach { (value, label) ->
                    FilterChip(selected = type == value, onClick = { type = value }, label = { Text(label) })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = categoryId == null, onClick = { categoryId = null }, label = { Text("全部分类") })
                categories.forEach { category -> FilterChip(selected = categoryId == category.id, onClick = { categoryId = category.id }, label = { Text(category.name) }) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${shown.size} 笔 · ${totalLabel(shown)}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Box {
                    TextButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(sort.label) }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        RecordSort.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { sortName = option.name; sortMenu = false }) }
                    }
                }
            }
        }
        if (shown.isEmpty()) item { EmptyState("没有符合条件的记录", if (expenses.isEmpty()) "点击「记一笔」添加消费。" else "试试其他关键词或调整筛选条件。") }
        items(shown, key = { it.expense.id }) { item -> ExpenseRow(item, categoryMap[item.expense.categoryId]?.name ?: "其他", today) { onOpen(item.expense.id) } }
    }
}

@Composable
internal fun StatsScreen(expenses: List<ExpenseDetails>, categories: List<Category>, today: LocalDate) {
    var periodName by rememberSaveable { mutableStateOf(Period.MONTH.name) }
    val period = Period.valueOf(periodName)
    val selected = remember(expenses, period, today) { inPeriod(expenses, period, today) }
    val total = totalMoney(selected)
    val grouped = selected.groupBy { it.expense.categoryId }.mapValues { totalMoney(it.value) }.toList().sortedByDescending { it.second }
    val categoryMap = categories.associateBy { it.id }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period.entries.forEach { option -> FilterChip(selected = period == option, onClick = { periodName = option.name }, label = { Text(option.label) }) }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${period.label}总消费", style = MaterialTheme.typography.titleMedium)
                    Text(totalLabel(selected), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("${selected.size} 笔付款", style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                    InfoRow("单次消费", totalLabel(selected.filter { it.expense.type == ExpenseType.SINGLE }))
                    InfoRow("多次消费", totalLabel(selected.filter { it.expense.type == ExpenseType.MULTI_USE }))
                }
            }
        }
        item { Text("按购买日期统计实际付款。记录使用只更新次数和单次成本，不重复计入支出。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        item { SectionTitle("分类分布") }
        if (selected.isEmpty()) item { EmptyState("这段时间还没有消费", "新增消费后，这里会显示分类金额和占比。") }
        items(grouped, key = { it.first }) { (categoryId, amount) ->
            val fraction = if (total.signum() > 0) amount.divide(total, 8, RoundingMode.HALF_UP) else BigDecimal.ZERO
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(categoryMap[categoryId]?.name ?: "其他", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Text("¥${amount.setScale(2).toPlainString()} · ${fraction.multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP)}%", style = MaterialTheme.typography.bodyMedium)
                }
                LinearProgressIndicator(progress = { fraction.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp), trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
internal fun SettingsScreen(settings: List<AppSetting>, categories: List<Category>, busy: Boolean, onTheme: (String) -> Unit, onExportBackup: () -> Unit, onImportBackup: () -> Unit, onExportCsv: () -> Unit) {
    val theme = settings.firstOrNull { it.key == "theme" }?.value ?: "SYSTEM"
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { SectionTitle("数据管理", "你的账本，由你保存和带走") }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                SettingAction(Icons.Default.SaveAlt, "导出完整备份", "保存全部消费、使用历史、分类与设置", !busy, onExportBackup)
                HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                SettingAction(Icons.Default.Restore, "恢复完整备份", "从备份文件完整恢复，覆盖当前数据", !busy, onImportBackup)
                HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                SettingAction(Icons.Default.TableChart, "导出 CSV", "供 Excel 阅读分析，不能用于恢复账本", !busy, onExportCsv)
            }
        }
        item {
            Text("换手机：在旧手机导出完整备份，将文件传到新手机，再选择恢复完整备份。备份文件未加密，请保存在自己信任的位置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { SectionTitle("外观") }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("SYSTEM" to "跟随系统", "LIGHT" to "浅色", "DARK" to "深色").forEach { (value, label) ->
                    FilterChip(selected = theme == value, onClick = { onTheme(value) }, enabled = !busy, label = { Text(label) })
                }
            }
        }
        item { SectionTitle("内置分类", "当前使用人民币（CNY）记账") }
        item { Text(categories.joinToString(" · ") { it.name }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))
            Text("花得值 1.0.0", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("日常开销，认真记录。长期消费，看见价值。\n无需账号，数据保存在这台设备上。建议定期导出完整备份，卸载前先备份账本。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingAction(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}
