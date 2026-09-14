package cn.student.expensetracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Category::class, Expense::class, MultiUseExpense::class, UsageRecord::class, AppSetting::class],
    version = LedgerDatabase.VERSION,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "personal_expenses.db"

        @Volatile private var instance: LedgerDatabase? = null

        fun build(context: Context): LedgerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, FILE_NAME)
                    // Version 1 is the initial schema. Future versions must add explicit
                    // Migration objects here; never clear a user's ledger on upgrade.
                    .build()
                    .also { instance = it }
            }
    }
}
