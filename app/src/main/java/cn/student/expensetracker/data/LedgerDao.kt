package cn.student.expensetracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** All mutations are internal to LedgerRepository's validated transactions. */
@Dao
interface LedgerDao {
    @Transaction
    @Query("SELECT * FROM expenses ORDER BY purchaseDate DESC, createdAt DESC, id")
    fun observeExpenses(): Flow<List<ExpenseDetails>>

    @Query("SELECT * FROM categories ORDER BY createdAt, id")
    fun observeCategories(): Flow<List<Category>>

    @Query("SELECT * FROM app_settings ORDER BY `key`")
    fun observeSettings(): Flow<List<AppSetting>>

    @Transaction
    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun details(id: String): ExpenseDetails?

    @Query("SELECT * FROM usage_records WHERE id = :id")
    suspend fun usage(id: String): UsageRecord?

    @Query("SELECT EXISTS(SELECT 1 FROM categories WHERE id = :id)")
    suspend fun categoryExists(id: String): Boolean

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun categoryCount(): Int

    @Query("SELECT * FROM categories ORDER BY id")
    suspend fun allCategories(): List<Category>

    @Query("SELECT * FROM expenses ORDER BY id")
    suspend fun allExpenses(): List<Expense>

    @Query("SELECT * FROM multi_use_expenses ORDER BY expenseId")
    suspend fun allMultiUse(): List<MultiUseExpense>

    @Query("SELECT * FROM usage_records ORDER BY id")
    suspend fun allUsages(): List<UsageRecord>

    @Query("SELECT * FROM app_settings ORDER BY `key`")
    suspend fun allSettings(): List<AppSetting>

    @Insert suspend fun insertCategories(items: List<Category>)
    @Insert suspend fun insertExpenses(items: List<Expense>)
    @Insert suspend fun insertMultiUse(items: List<MultiUseExpense>)
    @Insert suspend fun insertUsages(items: List<UsageRecord>)
    @Insert suspend fun insertSettings(items: List<AppSetting>)
    @Insert suspend fun insertUsage(item: UsageRecord)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefaultSettings(items: List<AppSetting>)

    // Upsert updates existing parents without REPLACE's implicit delete/cascade.
    @Upsert suspend fun upsertExpense(item: Expense)
    @Upsert suspend fun upsertMultiUse(item: MultiUseExpense)
    @Upsert suspend fun upsertSetting(item: AppSetting)
    @Update suspend fun updateUsage(item: UsageRecord): Int

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteExpense(id: String)

    @Query("DELETE FROM multi_use_expenses WHERE expenseId = :expenseId")
    suspend fun deleteMultiUse(expenseId: String)

    @Query("DELETE FROM usage_records WHERE id = :id")
    suspend fun deleteUsage(id: String)

    @Query("DELETE FROM usage_records") suspend fun clearUsages()
    @Query("DELETE FROM multi_use_expenses") suspend fun clearMultiUse()
    @Query("DELETE FROM expenses") suspend fun clearExpenses()
    @Query("DELETE FROM categories") suspend fun clearCategories()
    @Query("DELETE FROM app_settings") suspend fun clearSettings()
}
