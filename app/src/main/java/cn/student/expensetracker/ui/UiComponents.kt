package cn.student.expensetracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.student.expensetracker.data.ExpenseDetails
import cn.student.expensetracker.domain.Money
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

internal fun price(cents: Long): String = "¥${Money.format(cents)}"

/** BigDecimal keeps aggregate totals precise even for a very large restored ledger. */
internal fun totalMoney(records: List<ExpenseDetails>): BigDecimal = records.fold(BigDecimal.ZERO) { sum, item ->
    sum + BigDecimal.valueOf(item.expense.amountCents, 2)
}
internal fun totalLabel(records: List<ExpenseDetails>): String = "¥${totalMoney(records).setScale(2).toPlainString()}"
internal fun dateLabel(day: Long): String = LocalDate.ofEpochDay(day).toString()
internal fun shortDate(day: Long, today: LocalDate): String = when (day) {
    today.toEpochDay() -> "今天"
    today.minusDays(1).toEpochDay() -> "昨天"
    else -> dateLabel(day)
}
internal enum class Period(val label: String) { TODAY("今日"), WEEK("本周"), MONTH("本月"), YEAR("今年"), ALL("全部") }
internal fun inPeriod(records: List<ExpenseDetails>, period: Period, today: LocalDate): List<ExpenseDetails> {
    if (period == Period.ALL) return records
    val start = when (period) {
        Period.TODAY -> today
        Period.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Period.MONTH -> today.withDayOfMonth(1)
        Period.YEAR -> today.withDayOfYear(1)
        Period.ALL -> today
    }.toEpochDay()
    val end = when (period) {
        Period.TODAY -> today
        Period.WEEK -> LocalDate.ofEpochDay(start).plusDays(6)
        Period.MONTH -> today.with(TemporalAdjusters.lastDayOfMonth())
        Period.YEAR -> today.with(TemporalAdjusters.lastDayOfYear())
        Period.ALL -> today
    }.toEpochDay()
    return records.filter { it.expense.purchaseDate in start..end }
}

internal fun statusLabel(item: ExpenseDetails, today: LocalDate): String {
    val multi = item.multiUse ?: return "单次消费"
    return when {
        multi.totalUses != null && item.usedCount >= multi.totalUses -> "次数已用完"
        today.toEpochDay() < multi.startDate -> "尚未开始"
        multi.endDate != null && today.toEpochDay() > multi.endDate -> "已到期"
        multi.endDate != null && today.toEpochDay() == multi.endDate -> "今天到期"
        multi.endDate != null -> "${multi.endDate - today.toEpochDay()} 天后到期"
        else -> "长期有效"
    }
}
internal fun canUseNow(item: ExpenseDetails, today: LocalDate): Boolean = item.multiUse?.let { multi ->
    (multi.totalUses == null || item.usedCount < multi.totalUses) &&
        today.toEpochDay() >= multi.startDate && (multi.endDate == null || today.toEpochDay() <= multi.endDate)
} ?: false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = { Text(title, fontWeight = FontWeight.SemiBold) }, navigationIcon = {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }, actions = actions)
}

@Composable
internal fun SectionTitle(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
internal fun EmptyState(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.AutoMirrored.Filled.ReceiptLong, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(1.3f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun ExpenseRow(item: ExpenseDetails, category: String, today: LocalDate, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(category.take(1), Modifier.padding(horizontal = 14.dp, vertical = 11.dp), fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.expense.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text("$category · ${shortDate(item.expense.purchaseDate, today)}${if (item.multiUse != null) " · 多次" else ""}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(price(item.expense.amountCents), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun ActiveCard(item: ExpenseDetails, today: LocalDate, onClick: () -> Unit) {
    val multi = item.multiUse ?: return
    val average = Money.averageCostCents(item.expense.amountCents, item.usedCount)
    Card(onClick = onClick, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.expense.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.ChevronRight, null)
            }
            Text(average?.let { "${price(it)} / 次" } ?: "尚未使用", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("已使用 ${item.usedCount}${multi.totalUses?.let { " / $it" } ?: ""} 次 · ${statusLabel(item, today)}", style = MaterialTheme.typography.bodySmall)
            multi.totalUses?.let { total ->
                LinearProgressIndicator(progress = { (item.usedCount.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surface.copy(alpha = .5f))
                Text("剩余 ${(total - item.usedCount).coerceAtLeast(0)} 次 · 理论 ${Money.averageCostCents(item.expense.amountCents, total)?.let(::price)} / 次", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(label: String, epochDay: Long, enabled: Boolean = true, onChange: (Long) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(16.dp)) {
        Icon(Icons.Default.DateRange, null, Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f))
        Text(dateLabel(epochDay))
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = 1900..2200)
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onChange(java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()) }
                open = false
            }, enabled = state.selectedDateMillis != null) { Text("确定") }
        }, dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } }) { DatePicker(state = state) }
    }
}
