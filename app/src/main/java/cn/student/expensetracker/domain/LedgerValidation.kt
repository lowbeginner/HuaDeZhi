package cn.student.expensetracker.domain

import cn.student.expensetracker.data.AppSetting
import cn.student.expensetracker.data.Expense
import cn.student.expensetracker.data.ExpenseType
import cn.student.expensetracker.data.LedgerSnapshot
import cn.student.expensetracker.data.MultiUseExpense
import cn.student.expensetracker.data.MultiUseType
import cn.student.expensetracker.data.UsageRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Shared by editing, transactional repository writes and backup preflight. */
object LedgerValidation {
    const val MAX_USES = 1_000_000
    const val MAX_RECORDS = 1_000_000
    private val minDay = LocalDate.of(1900, 1, 1).toEpochDay()
    private val maxDay = LocalDate.of(2200, 12, 31).toEpochDay()
    private val minTimestamp = LocalDate.of(1900, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val maxTimestamp = LocalDate.of(2201, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1

    /**
     * Persistent data must remain portable between time zones and devices with
     * different clocks. Check numeric bounds and relationships, never reinterpret
     * recorded instants against the importing phone's current date/time.
     */
    fun validateSnapshot(
        snapshot: LedgerSnapshot,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ) {
        require(snapshot.categories.isNotEmpty() && snapshot.categories.size <= 10_000) { "分类数据缺失或数量异常" }
        require(snapshot.expenses.size <= MAX_RECORDS && snapshot.multiUse.size <= MAX_RECORDS &&
            snapshot.usageRecords.size <= MAX_RECORDS && snapshot.settings.size <= 256) { "备份记录数量过多" }
        unique(snapshot.categories.map { it.id }, "分类")
        unique(snapshot.expenses.map { it.id }, "消费")
        unique(snapshot.multiUse.map { it.expenseId }, "多次消费")
        unique(snapshot.usageRecords.map { it.id }, "使用记录")
        unique(snapshot.settings.map { it.key }, "设置")

        snapshot.categories.forEach {
            identifier(it.id, "分类 ID")
            requiredText(it.name, 50, "分类名称")
            timestamp(it.createdAt, "分类创建时间")
        }
        snapshot.settings.forEach(::validateSetting)
        val settings = snapshot.settings.associate { it.key to it.value }
        require(settings.containsKey("theme") && settings.containsKey("currency")) { "备份缺少主题或货币设置" }

        val categoryIds = snapshot.categories.mapTo(HashSet()) { it.id }
        val expenseIds = snapshot.expenses.mapTo(HashSet()) { it.id }
        val multiById = snapshot.multiUse.associateBy { it.expenseId }
        val usageByExpense = snapshot.usageRecords.groupBy { it.expenseId }
        snapshot.multiUse.forEach {
            require(it.expenseId in expenseIds) { "多次消费缺少对应的消费记录" }
        }
        snapshot.usageRecords.forEach {
            require(it.expenseId in multiById) { "使用记录缺少对应的多次消费" }
        }
        snapshot.expenses.forEach {
            require(it.categoryId in categoryIds) { "消费引用了不存在的分类" }
            validateExpense(it, multiById[it.id], usageByExpense[it.id].orEmpty(), nowMillis, zoneId)
        }
    }

    @Suppress("UNUSED_PARAMETER") // Keep the shared call signature; existing instants are not checked against now.
    fun validateExpense(
        expense: Expense,
        multiUse: MultiUseExpense?,
        existingUsages: List<UsageRecord> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        checkExistingUsageDates: Boolean = false,
    ) {
        identifier(expense.id, "消费 ID")
        identifier(expense.categoryId, "分类 ID")
        requiredText(expense.name, 120, "消费名称")
        amount(expense.amountCents, "金额")
        day(expense.purchaseDate, "购买日期")
        note(expense.note)
        timestamp(expense.createdAt, "创建时间")
        timestamp(expense.updatedAt, "更新时间")
        require(expense.updatedAt >= expense.createdAt) { "更新时间不能早于创建时间" }
        require(expense.type == ExpenseType.SINGLE || expense.type == ExpenseType.MULTI_USE) { "消费类型不正确" }

        if (expense.type == ExpenseType.SINGLE) {
            require(multiUse == null && existingUsages.isEmpty()) { "有使用历史的消费不能直接改为单次消费" }
            return
        }
        requireNotNull(multiUse) { "多次消费信息缺失" }
        require(multiUse.expenseId == expense.id) { "多次消费关联 ID 不正确" }
        day(multiUse.startDate, "开始日期")
        multiUse.endDate?.let {
            day(it, "到期日期")
            require(it >= multiUse.startDate) { "到期日期不能早于开始日期" }
        }
        when (multiUse.multiUseType) {
            MultiUseType.LIMITED -> require(multiUse.totalUses != null && multiUse.totalUses in 1..MAX_USES) {
                "总次数必须为 1 至 1000000 的整数"
            }
            MultiUseType.UNLIMITED -> require(multiUse.totalUses == null) { "不限次数项目不应设置总次数" }
            else -> throw IllegalArgumentException("使用类型不正确")
        }
        multiUse.referenceSinglePriceCents?.let { amount(it, "参考单次价格") }
        require(existingUsages.size <= MAX_USES) { "使用记录数量过多" }
        multiUse.totalUses?.let { require(existingUsages.size <= it) { "总次数不能小于已有的使用次数" } }
        existingUsages.forEach {
            validateStoredUsage(it, multiUse)
            // Re-evaluate history only when the user explicitly edits validity.
            // A name/amount/category edit after travel must preserve old uses.
            if (checkExistingUsageDates) validateUsageWindow(it, multiUse, zoneId)
        }
    }

    fun validateUsage(
        usage: UsageRecord,
        multiUse: MultiUseExpense,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ) {
        validateStoredUsage(usage, multiUse)
        require(usage.usageDate <= nowMillis) { "使用时间不能晚于当前时间" }
        validateUsageWindow(usage, multiUse, zoneId)
    }

    private fun validateStoredUsage(usage: UsageRecord, multiUse: MultiUseExpense) {
        identifier(usage.id, "使用记录 ID")
        require(usage.expenseId == multiUse.expenseId) { "使用记录关联的消费不正确" }
        timestamp(usage.usageDate, "使用时间")
        timestamp(usage.createdAt, "使用记录创建时间")
        note(usage.note)
    }

    private fun validateUsageWindow(usage: UsageRecord, multiUse: MultiUseExpense, zoneId: ZoneId) {
        val usageDay = Instant.ofEpochMilli(usage.usageDate).atZone(zoneId).toLocalDate().toEpochDay()
        require(usageDay >= multiUse.startDate) { "使用日期不能早于项目开始日期" }
        require(multiUse.endDate == null || usageDay <= multiUse.endDate) { "使用日期不能晚于项目到期日期" }
    }

    fun validateSetting(setting: AppSetting) {
        identifier(setting.key, "设置名称")
        require(setting.value.length <= 4096) { "设置值过长" }
        when (setting.key) {
            "theme" -> require(setting.value in setOf("SYSTEM", "LIGHT", "DARK")) { "主题设置不正确" }
            "currency" -> require(setting.value == "CNY") { "当前版本仅支持人民币 CNY" }
        }
    }

    private fun unique(values: List<String>, label: String) {
        require(values.size == values.toSet().size) { "${label}存在重复 ID" }
    }

    private fun identifier(value: String, label: String) = requiredText(value, 128, label)

    private fun requiredText(value: String, limit: Int, label: String) {
        require(value.isNotBlank() && value.length <= limit && '\u0000' !in value) { "${label}为空、过长或包含非法字符" }
    }

    private fun note(value: String) {
        require(value.length <= 10_000 && '\u0000' !in value) { "备注过长或包含非法字符" }
    }

    private fun amount(value: Long, label: String) {
        require(value in 1..Money.MAX_AMOUNT_CENTS) { "${label}必须大于 0 且不超过 999999999.99 元" }
    }

    private fun day(value: Long, label: String) {
        require(value in minDay..maxDay) { "${label}须在 1900 至 2200 年之间" }
    }

    private fun timestamp(value: Long, label: String) {
        require(value in minTimestamp..maxTimestamp) { "${label}超出支持范围" }
    }
}
