package cn.student.expensetracker.data

import androidx.room.withTransaction
import cn.student.expensetracker.domain.LedgerValidation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.UUID

class LedgerRepository(private val database: LedgerDatabase) {
    private val dao = database.ledgerDao()

    /** Safe to call repeatedly or concurrently; seeding is a single transaction. */
    suspend fun initialize() = database.withTransaction {
        if (dao.categoryCount() == 0) dao.insertCategories(LedgerDefaults.categories())
        dao.insertDefaultSettings(LedgerDefaults.settings)
    }

    fun observeExpenses(): Flow<List<ExpenseDetails>> = flow {
        initialize()
        emitAll(dao.observeExpenses())
    }

    fun observeCategories(): Flow<List<Category>> = flow {
        initialize()
        emitAll(dao.observeCategories())
    }

    fun observeSettings(): Flow<List<AppSetting>> = flow {
        initialize()
        emitAll(dao.observeSettings())
    }

    suspend fun saveExpense(expense: Expense, multiUse: MultiUseExpense? = null) = database.withTransaction {
        val existing = dao.details(expense.id)
        val now = System.currentTimeMillis()
        val storedExpense = expense.copy(
            createdAt = existing?.expense?.createdAt ?: expense.createdAt,
            updatedAt = maxOf(now, existing?.expense?.createdAt ?: expense.createdAt),
        )
        require(dao.categoryExists(storedExpense.categoryId)) { "所选分类不存在，请重新选择" }
        val previousValidity = existing?.multiUse
        val validityChanged = previousValidity != null && multiUse != null &&
            (previousValidity.startDate != multiUse.startDate || previousValidity.endDate != multiUse.endDate)
        LedgerValidation.validateExpense(
            storedExpense, multiUse, existing?.usages.orEmpty(), now,
            checkExistingUsageDates = validityChanged,
        )
        dao.upsertExpense(storedExpense)
        if (multiUse != null) dao.upsertMultiUse(multiUse) else dao.deleteMultiUse(expense.id)
    }

    /** The caller obtains user confirmation; foreign keys cascade to all usage rows. */
    suspend fun deleteExpense(id: String) = database.withTransaction { dao.deleteExpense(id) }

    suspend fun addUsage(
        expenseId: String,
        usageDate: Long = System.currentTimeMillis(),
        note: String = "",
    ): UsageRecord = database.withTransaction {
        val details = requireNotNull(dao.details(expenseId)) { "消费记录不存在" }
        val multiUse = requireNotNull(details.multiUse) { "只有多次消费可以添加使用记录" }
        require(details.usedCount < LedgerValidation.MAX_USES) { "使用记录数量已达上限" }
        multiUse.totalUses?.let { require(details.usedCount < it) { "次数已用完，可先编辑总次数" } }
        val now = System.currentTimeMillis()
        val usage = UsageRecord(UUID.randomUUID().toString(), expenseId, usageDate, note, now)
        LedgerValidation.validateUsage(usage, multiUse, now)
        dao.insertUsage(usage)
        usage
    }

    suspend fun updateUsage(record: UsageRecord) = database.withTransaction {
        val old = requireNotNull(dao.usage(record.id)) { "使用记录不存在" }
        require(record.expenseId == old.expenseId) { "不能将使用记录移动到其他消费" }
        val details = requireNotNull(dao.details(record.expenseId)) { "消费记录不存在" }
        val multiUse = requireNotNull(details.multiUse) { "多次消费信息不存在" }
        val stored = record.copy(createdAt = old.createdAt)
        LedgerValidation.validateUsage(stored, multiUse)
        check(dao.updateUsage(stored) == 1) { "使用记录更新失败" }
    }

    suspend fun deleteUsage(id: String) = database.withTransaction { dao.deleteUsage(id) }

    suspend fun setSetting(key: String, value: String) = database.withTransaction {
        val setting = AppSetting(key, value)
        LedgerValidation.validateSetting(setting)
        dao.upsertSetting(setting)
    }

    /** All tables are read in one transaction, so a concurrent edit cannot split a backup. */
    suspend fun snapshot(): LedgerSnapshot = database.withTransaction {
        initialize()
        LedgerSnapshot(dao.allCategories(), dao.allExpenses(), dao.allMultiUse(), dao.allUsages(), dao.allSettings())
    }

    /** Validate before deleting; any insert failure or cancellation rolls back all tables. */
    suspend fun restore(snapshot: LedgerSnapshot) {
        LedgerValidation.validateSnapshot(snapshot)
        database.withTransaction {
            dao.clearUsages()
            dao.clearMultiUse()
            dao.clearExpenses()
            dao.clearCategories()
            dao.clearSettings()
            dao.insertCategories(snapshot.categories)
            dao.insertExpenses(snapshot.expenses)
            dao.insertMultiUse(snapshot.multiUse)
            dao.insertUsages(snapshot.usageRecords)
            dao.insertSettings(snapshot.settings)
        }
    }
}
