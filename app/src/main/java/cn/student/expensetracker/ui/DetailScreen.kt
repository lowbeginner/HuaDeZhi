package cn.student.expensetracker.ui

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.student.expensetracker.data.*
import cn.student.expensetracker.domain.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DetailScreen(item: ExpenseDetails, category: String, today: LocalDate, busy: Boolean,
    onBack: () -> Unit, onEdit: () -> Unit, onDeleteExpense: () -> Unit,
    onAddUsage: (Long, String, () -> Unit) -> Unit,
    onUpdateUsage: (UsageRecord, () -> Unit) -> Unit,
    onDeleteUsage: (String, () -> Unit) -> Unit,
) {
    val expense = item.expense
    val multi = item.multiUse
    var deleteExpense by rememberSaveable { mutableStateOf(false) }
    var editUsageId by rememberSaveable { mutableStateOf<String?>(null) }
    var addHistory by rememberSaveable { mutableStateOf(false) }
    var deleteUsageId by rememberSaveable { mutableStateOf<String?>(null) }
    val average = Money.averageCostCents(expense.amountCents, item.usedCount)
    val canAdd = multi != null && (multi.totalUses == null || item.usedCount < multi.totalUses)
    BackHandler(enabled = busy) { }
    Scaffold(topBar = {
        PageBar("消费详情", if (busy) null else onBack) {
            IconButton(onClick = onEdit, enabled = !busy, modifier = Modifier.testTag("edit_expense")) { Icon(Icons.Default.Edit, "编辑消费") }
            IconButton(onClick = { deleteExpense = true }, enabled = !busy) { Icon(Icons.Default.DeleteOutline, "删除消费") }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Text(expense.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("$category · ${if (multi == null) "单次消费" else if (multi.multiUseType == MultiUseType.LIMITED) "固定次数" else "不限次数"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (multi != null) "当前实际单次成本" else "消费金额", style = MaterialTheme.typography.titleSmall)
                        Text(if (multi == null) price(expense.amountCents) else average?.let { "${price(it)} / 次" } ?: "尚未使用", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("detail_cost"))
                        if (multi != null) {
                            Text("已使用 ${item.usedCount}${multi.totalUses?.let { " / $it" } ?: ""} 次", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("detail_used_count"))
                            Text(statusLabel(item, today), style = MaterialTheme.typography.bodyMedium)
                            if (multi.totalUses != null) {
                                LinearProgressIndicator(progress = { (item.usedCount.toFloat() / multi.totalUses).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                InfoRow("剩余次数", "${(multi.totalUses - item.usedCount).coerceAtLeast(0)} 次")
                                InfoRow("理论单次成本", "${Money.averageCostCents(expense.amountCents, multi.totalUses)?.let(::price)} / 次")
                            }
                        }
                    }
                }
            }
            if (multi != null) {
                item {
                    Button(onClick = { onAddUsage(System.currentTimeMillis(), "") {} }, enabled = !busy && canUseNow(item, today), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("use_once")) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "正在保存…" else if (canUseNow(item, today)) "使用一次" else statusLabel(item, today))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("记录使用不会增加消费金额。历史使用可在下方补录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(18.dp)) {
                        InfoRow(if (multi == null) "金额" else "购买总金额", price(expense.amountCents))
                        InfoRow("分类", category)
                        InfoRow(if (multi == null) "消费日期" else "购买日期", dateLabel(expense.purchaseDate))
                        if (multi != null) {
                            InfoRow("开始日期", dateLabel(multi.startDate))
                            InfoRow("到期日期", multi.endDate?.let(::dateLabel) ?: "未设置")
                            InfoRow("有效期状态", statusLabel(item, today))
                            if (multi.endDate != null && today.toEpochDay() in multi.startDate..multi.endDate) InfoRow("剩余有效期", "${multi.endDate - today.toEpochDay()} 天（到期当天可用）")
                        }
                        InfoRow("备注", expense.note.ifBlank { "无" })
                    }
                }
            }
            if (multi?.referenceSinglePriceCents != null) {
                val reference = multi.referenceSinglePriceCents
                val target = Money.breakEvenUses(expense.amountCents, reference) ?: 0
                val remaining = (target - item.usedCount).coerceAtLeast(0)
                item {
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionTitle("回本进度")
                            Text(if (remaining == 0L) "已回本" else "再用 $remaining 次回本", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            InfoRow("参考单次价格", "${price(reference)} / 次")
                            InfoRow("回本目标", "$target 次")
                            LinearProgressIndicator(progress = { if (target == 0L) 1f else (item.usedCount.toFloat() / target).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            if (remaining == 0L) {
                                val saving = BigDecimal.valueOf(reference, 2).multiply(BigDecimal(item.usedCount)).subtract(BigDecimal.valueOf(expense.amountCents, 2))
                                InfoRow("理论节省", "¥${saving.setScale(2).toPlainString()}")
                            }
                            Text("按参考单次购买价格估算。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (multi != null) {
                item { SectionTitle("使用历史", "共 ${item.usedCount} 条", trailing = { TextButton(onClick = { addHistory = true }, enabled = !busy && canAdd, modifier = Modifier.testTag("add_history")) { Icon(Icons.Default.History, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("补录") } }) }
                if (item.usages.isEmpty()) item { EmptyState("还没有使用记录", "点击「使用一次」，或补录过去的使用。") }
                items(item.usages.sortedByDescending { it.usageDate }, key = { it.id }) { usage ->
                    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(Instant.ofEpochMilli(usage.usageDate).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                if (usage.note.isNotBlank()) Text(usage.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { editUsageId = usage.id }, enabled = !busy) { Icon(Icons.Default.Edit, "编辑使用记录", Modifier.size(20.dp)) }
                            IconButton(onClick = { deleteUsageId = usage.id }, enabled = !busy) { Icon(Icons.Default.DeleteOutline, "删除使用记录", Modifier.size(20.dp)) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    if (deleteExpense) AlertDialog(onDismissRequest = { if (!busy) deleteExpense = false }, title = { Text("删除这笔消费？") }, text = {
        Text("删除「${expense.name}」${if (multi != null) "后，相关的 ${item.usedCount} 条使用记录也会一起删除" else "后将无法找回"}。该操作无法撤销。")
    }, confirmButton = { TextButton(onClick = onDeleteExpense, enabled = !busy, modifier = Modifier.testTag("confirm_delete_expense")) { Text("确认删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleteExpense = false }, enabled = !busy) { Text("取消") } })
    if (deleteUsageId != null) AlertDialog(onDismissRequest = { if (!busy) deleteUsageId = null }, title = { Text("删除这次使用？") }, text = { Text("删除后，使用次数和单次成本会重新计算。该操作无法撤销。") }, confirmButton = {
        TextButton(onClick = { deleteUsageId?.let { onDeleteUsage(it) { deleteUsageId = null } } }, enabled = !busy) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
    }, dismissButton = { TextButton(onClick = { deleteUsageId = null }, enabled = !busy) { Text("取消") } })
    val editing = item.usages.firstOrNull { it.id == editUsageId }
    if (multi != null && (addHistory || editing != null)) UsageEditor(item, editing, busy, onDismiss = { addHistory = false; editUsageId = null }, onSave = { timestamp, note ->
        val done = { addHistory = false; editUsageId = null }
        if (editing != null) onUpdateUsage(editing.copy(usageDate = timestamp, note = note), done) else onAddUsage(timestamp, note, done)
    })
}

@Composable
private fun UsageEditor(item: ExpenseDetails, usage: UsageRecord?, busy: Boolean, onDismiss: () -> Unit, onSave: (Long, String) -> Unit) {
    val context = LocalContext.current
    val multi = item.multiUse ?: return
    val initial = usage?.let { Instant.ofEpochMilli(it.usageDate).atZone(ZoneId.systemDefault()).toLocalDateTime() } ?: LocalDateTime.now()
    var date by rememberSaveable(usage?.id) { mutableLongStateOf(initial.toLocalDate().toEpochDay().coerceAtLeast(multi.startDate).let { if (multi.endDate != null) it.coerceAtMost(multi.endDate) else it }) }
    var hour by rememberSaveable(usage?.id) { mutableIntStateOf(initial.hour) }
    var minute by rememberSaveable(usage?.id) { mutableIntStateOf(initial.minute) }
    var note by rememberSaveable(usage?.id) { mutableStateOf(usage?.note ?: "") }
    var error by rememberSaveable(usage?.id) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (usage == null) "补录使用" else "编辑使用记录") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DateField("使用日期", date, !busy) { date = it; error = null }
            OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> hour = h; minute = m; error = null }, hour, minute, true).show() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Schedule, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("使用时间", Modifier.weight(1f)); Text("${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}")
            }
            OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注（可选）") }, enabled = !busy, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
            Text("使用时间应在项目有效期内，且不晚于当前时间。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(enabled = !busy, onClick = {
            runCatching {
                require(date >= multi.startDate && (multi.endDate == null || date <= multi.endDate)) { "使用日期必须在项目有效期内" }
                require(note.length <= 10_000) { "备注不能超过 10000 个字" }
                val timestamp = LocalDate.ofEpochDay(date).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                require(timestamp <= System.currentTimeMillis()) { "不能记录未来的使用" }
                timestamp
            }.onSuccess { onSave(it, note.trim()) }.onFailure { error = it.message ?: "请检查使用时间" }
        }) { Text(if (busy) "正在保存…" else "保存") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}
