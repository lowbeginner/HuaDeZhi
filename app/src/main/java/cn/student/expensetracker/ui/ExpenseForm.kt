package cn.student.expensetracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.student.expensetracker.data.*
import cn.student.expensetracker.domain.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Composable
internal fun ExpenseForm(item: ExpenseDetails?, initialType: String, categories: List<Category>, busy: Boolean, onBack: () -> Unit, onSave: (Expense, MultiUseExpense?) -> Unit) {
    val type = item?.expense?.type ?: initialType
    val isMulti = type == ExpenseType.MULTI_USE
    val existing = item?.expense
    val oldMulti = item?.multiUse
    val today = LocalDate.now().toEpochDay()
    val id by rememberSaveable { mutableStateOf(existing?.id ?: UUID.randomUUID().toString()) }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var amount by rememberSaveable { mutableStateOf(existing?.amountCents?.let(Money::format) ?: "") }
    var categoryId by rememberSaveable { mutableStateOf(existing?.categoryId ?: categories.firstOrNull()?.id.orEmpty()) }
    var purchase by rememberSaveable { mutableLongStateOf(existing?.purchaseDate ?: today) }
    var start by rememberSaveable { mutableLongStateOf(oldMulti?.startDate ?: today) }
    var hasEnd by rememberSaveable { mutableStateOf(oldMulti?.endDate != null) }
    var end by rememberSaveable { mutableLongStateOf(oldMulti?.endDate ?: LocalDate.now().plusYears(1).minusDays(1).toEpochDay()) }
    var limited by rememberSaveable { mutableStateOf(oldMulti?.multiUseType == MultiUseType.LIMITED) }
    var totalUses by rememberSaveable { mutableStateOf(oldMulti?.totalUses?.toString() ?: "") }
    var reference by rememberSaveable { mutableStateOf(oldMulti?.referenceSinglePriceCents?.let(Money::format) ?: "") }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = busy) { }
    Scaffold(topBar = { PageBar(if (existing == null) (if (isMulti) "新增多次消费" else "新增单次消费") else "编辑消费", if (busy) null else onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (isMulti) "一笔购买，每次使用都看得见。" else "记录一笔真实发生的开销。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = name, onValueChange = { name = it; error = null }, label = { Text(if (isMulti) "项目名称 *" else "消费名称 *") }, placeholder = { Text(if (isMulti) "例如：健身年卡" else "例如：晚饭") }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().testTag("expense_name"))
            OutlinedTextField(value = amount, onValueChange = { amount = it; error = null }, label = { Text(if (isMulti) "总金额（元）*" else "金额（元）*") }, prefix = { Text("¥ ") }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next), supportingText = { Text("请输入大于 0 的金额，最多两位小数") }, modifier = Modifier.fillMaxWidth().testTag("expense_amount"))
            Text("分类", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { category -> FilterChip(selected = categoryId == category.id, onClick = { categoryId = category.id }, enabled = !busy, label = { Text(category.name) }) }
            }
            DateField(if (isMulti) "购买日期" else "消费日期", purchase, !busy) { purchase = it }
            if (isMulti) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SectionTitle("使用规则")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !limited, onClick = { limited = false }, enabled = !busy, label = { Text("不限次数") })
                    FilterChip(selected = limited, onClick = { limited = true }, enabled = !busy,
                        label = { Text("固定次数") }, modifier = Modifier.testTag("multi_limited"))
                }
                if (limited) OutlinedTextField(value = totalUses, onValueChange = { totalUses = it; error = null }, label = { Text("总次数 *") }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), supportingText = { Text("已使用 ${item?.usedCount ?: 0} 次；总次数不能少于已使用次数") }, modifier = Modifier.fillMaxWidth().testTag("expense_total_uses"))
                DateField("开始日期", start, !busy) { start = it }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("设置到期日期", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = hasEnd, onCheckedChange = { hasEnd = it }, enabled = !busy)
                }
                if (hasEnd) DateField("到期日期（当天有效）", end, !busy) { end = it }
                OutlinedTextField(value = reference, onValueChange = { reference = it; error = null }, label = { Text("参考单次价格（元，可选）") }, prefix = { Text("¥ ") }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next), supportingText = { Text("填写后自动计算回本次数和理论节省金额") }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注（可选）") }, minLines = 3, maxLines = 6, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("expense_note"))
            if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("expense_form_error"))
            Button(onClick = {
                val result = runCatching {
                    require(name.trim().isNotEmpty()) { "请输入消费名称" }
                    require(name.trim().length <= 120) { "名称不能超过 120 个字" }
                    require(note.length <= 10_000) { "备注不能超过 10000 个字" }
                    require(categories.any { it.id == categoryId }) { "请选择有效分类" }
                    val cents = Money.parseCents(amount)
                    require(cents > 0) { "金额必须大于 0" }
                    val total = if (isMulti && limited) totalUses.toIntOrNull() else null
                    if (isMulti && limited) {
                        require(total != null && total in 1..1_000_000) { "总次数必须是 1 至 1000000 的整数" }
                        require(total >= (item?.usedCount ?: 0)) { "总次数不能小于已使用次数 ${item?.usedCount ?: 0}" }
                    }
                    if (isMulti && hasEnd) require(end >= start) { "到期日期不能早于开始日期" }
                    val referenceCents = if (isMulti && reference.isNotBlank()) Money.parseCents(reference).also { require(it > 0) { "参考单次价格必须大于 0" } } else null
                    val validityChanged = oldMulti != null && (oldMulti.startDate != start || oldMulti.endDate != if (hasEnd) end else null)
                    if (isMulti && validityChanged) item?.usages?.forEach { usage ->
                        val day = Instant.ofEpochMilli(usage.usageDate).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
                        require(day >= start && (!hasEnd || day <= end)) { "已有使用记录不在新的有效期内，请先调整使用记录" }
                    }
                    val now = System.currentTimeMillis()
                    val expense = Expense(id = id, name = name.trim(), amountCents = cents, categoryId = categoryId, purchaseDate = purchase, note = note.trim(), type = type, createdAt = existing?.createdAt ?: now, updatedAt = now)
                    val multi = if (isMulti) MultiUseExpense(expenseId = id, multiUseType = if (limited) MultiUseType.LIMITED else MultiUseType.UNLIMITED, startDate = start, endDate = if (hasEnd) end else null, totalUses = total, referenceSinglePriceCents = referenceCents) else null
                    expense to multi
                }
                result.onSuccess { (expense, multi) -> error = null; onSave(expense, multi) }.onFailure { error = it.message ?: "请检查输入内容" }
            }, enabled = !busy && categories.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("save_expense")) {
                if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                Text(if (busy) "正在保存…" else "保存消费")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
