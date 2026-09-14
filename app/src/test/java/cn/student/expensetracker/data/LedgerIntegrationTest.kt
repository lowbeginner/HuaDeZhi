package cn.student.expensetracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.student.expensetracker.backup.BackupCodec
import cn.student.expensetracker.domain.Money
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LedgerIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "ledger-test-${UUID.randomUUID()}.db"
    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    private val today = LocalDate.now()

    @Before fun open() = runBlocking {
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        repo = LedgerRepository(db)
        repo.initialize()
    }

    @After fun close() { db.close(); context.deleteDatabase(name) }

    private fun expense(id: String, amount: Long, type: String = ExpenseType.SINGLE) = Expense(
        id, when(id) { "gym" -> "健身年卡"; "swim" -> "游泳卡"; else -> id }, amount,
        if (type == ExpenseType.MULTI_USE) "fitness" else "food", today.minusDays(90).toEpochDay(),
        "测试备注，保留中文与换行\n第二行", type, System.currentTimeMillis(), System.currentTimeMillis(),
    )

    private fun usageTime(daysAgo: Long): Long = today.minusDays(daysAgo).atTime(12, 30)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private suspend fun populateFixture() {
        repo.saveExpense(expense("晚饭", 2800))
        repo.saveExpense(expense("打车", 1600).copy(categoryId = "transport"))
        repo.saveExpense(expense("咖啡", 1800))
        repo.saveExpense(expense("gym", 120000, ExpenseType.MULTI_USE), MultiUseExpense(
            "gym", MultiUseType.UNLIMITED, today.minusDays(90).toEpochDay(),
            today.plusDays(200).toEpochDay(), null, 5000,
        ))
        repo.saveExpense(expense("swim", 60000, ExpenseType.MULTI_USE), MultiUseExpense(
            "swim", MultiUseType.LIMITED, today.minusDays(90).toEpochDay(), null, 30, null,
        ))
        repeat(53) { repo.addUsage("gym", usageTime(80L - it), "健身第 ${it + 1} 次") }
        repeat(12) { repo.addUsage("swim", usageTime(30L - it), "游泳第 ${it + 1} 次") }
        repo.setSetting("theme", "DARK")
        repo.setSetting("futureSetting", "保留未来扩展的用户设置")
    }

    @Test fun exportDeleteRestoreAndReopenPreservesEveryRowAndCost() = runBlocking {
        populateFixture()
        val original = repo.snapshot()
        val output = ByteArrayOutputStream()
        BackupCodec().write(original, output)
        original.expenses.forEach { repo.deleteExpense(it.id) }
        assertTrue(repo.snapshot().expenses.isEmpty())
        assertTrue(repo.snapshot().multiUse.isEmpty())
        assertTrue(repo.snapshot().usageRecords.isEmpty())
        repo.setSetting("theme", "LIGHT")
        repo.restore(BackupCodec().read(ByteArrayInputStream(output.toByteArray())).snapshot)
        assertEquals(original, repo.snapshot())

        // Close SQLite completely and reopen the on-disk database, as after process death.
        db.close()
        db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        repo = LedgerRepository(db)
        assertEquals(original, repo.snapshot())
        val details = repo.observeExpenses().first().associateBy { it.expense.id }
        assertEquals(5, details.size)
        assertEquals(53, details.getValue("gym").usedCount)
        assertEquals(2264L, Money.averageCostCents(120000, details.getValue("gym").usedCount))
        assertEquals(12, details.getValue("swim").usedCount)
        assertEquals(30, details.getValue("swim").multiUse!!.totalUses)
        assertEquals(5000L, Money.averageCostCents(60000, 12))
        assertEquals(2000L, Money.averageCostCents(60000, 30))
        assertEquals(186200L, original.expenses.sumOf { it.amountCents })
        repo.addUsage("gym", usageTime(1), "恢复后的使用")
        assertEquals(186200L, repo.snapshot().expenses.sumOf { it.amountCents })
    }

    @Test fun restoreInsertFailureRollsBackAllDeletesAndPartialInserts() = runBlocking {
        populateFixture()
        val incoming = repo.snapshot()
        incoming.expenses.forEach { repo.deleteExpense(it.id) }
        repo.saveExpense(expense("原手机现有账本", 100))
        repo.setSetting("theme", "LIGHT")
        val before = repo.snapshot()
        // A real SQLite failure after restore has already deleted and inserted parent rows.
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_restore BEFORE INSERT ON usage_records
            BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END
        """.trimIndent())
        var failed = false
        try { repo.restore(incoming) } catch (_: Exception) { failed = true }
        assertTrue("The SQLite fault must actually interrupt restore", failed)
        assertEquals("The old ledger must survive intact", before, repo.snapshot())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_restore")
        repo.restore(incoming)
        assertEquals(incoming, repo.snapshot())
    }

    @Test fun parallelUsageCannotExceedCardLimitAndDeleteReopensCapacity() = runBlocking {
        val card = expense("swim", 60000, ExpenseType.MULTI_USE)
        val multi = MultiUseExpense("swim", MultiUseType.LIMITED, today.minusDays(90).toEpochDay(), null, 1, null)
        repo.saveExpense(card, multi)
        val outcomes = coroutineScope {
            (1..8).map { async { runCatching { repo.addUsage("swim", usageTime(1)) }.isSuccess } }.awaitAll()
        }
        assertEquals(1, outcomes.count { it })
        val record = repo.snapshot().usageRecords.single()
        repo.deleteUsage(record.id)
        assertEquals(0, repo.observeExpenses().first().single().usedCount)
        assertNull(Money.averageCostCents(60000, 0))
        repo.addUsage("swim", usageTime(2))
        assertEquals(1, repo.observeExpenses().first().single().usedCount)
    }

    @Test fun editingParentsPreservesUsagesAndInvalidChangesKeepOriginalData() = runBlocking {
        populateFixture()
        val gym = repo.observeExpenses().first().first { it.expense.id == "gym" }
        repo.saveExpense(gym.expense.copy(name = "健身卡新名称"), gym.multiUse)
        assertEquals(53, repo.snapshot().usageRecords.count { it.expenseId == "gym" })
        val original = repo.snapshot()
        try {
            repo.saveExpense(gym.expense, gym.multiUse!!.copy(multiUseType = MultiUseType.LIMITED, totalUses = 1))
            fail("Should reject a total below recorded usage")
        } catch (_: IllegalArgumentException) { }
        assertEquals(original, repo.snapshot())
        val record = original.usageRecords.first { it.expenseId == "gym" }
        repo.updateUsage(record.copy(usageDate = usageTime(2), note = "补正日期"))
        assertEquals(53, repo.snapshot().usageRecords.count { it.expenseId == "gym" })
        repo.deleteExpense("gym")
        val deleted = repo.snapshot()
        assertFalse(deleted.multiUse.any { it.expenseId == "gym" })
        assertFalse(deleted.usageRecords.any { it.expenseId == "gym" })
        assertEquals(12, deleted.usageRecords.count { it.expenseId == "swim" })
    }

    @Test fun invalidBackupNeverTouchesCurrentDatabase() = runBlocking {
        populateFixture()
        val before = repo.snapshot()
        val invalid = before.copy(expenses = before.expenses.map { it.copy(categoryId = "missing") })
        try { repo.restore(invalid); fail("Must reject broken foreign key") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, repo.snapshot())
    }
}
