package cn.student.expensetracker.domain

import cn.student.expensetracker.data.AppSetting
import cn.student.expensetracker.data.Category
import cn.student.expensetracker.data.Expense
import cn.student.expensetracker.data.ExpenseDetails
import cn.student.expensetracker.data.ExpenseType
import cn.student.expensetracker.data.LedgerDefaults
import cn.student.expensetracker.data.LedgerSnapshot
import cn.student.expensetracker.data.MultiUseExpense
import cn.student.expensetracker.data.MultiUseType
import cn.student.expensetracker.data.UsageRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LedgerValidationTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-09-13T12:00:00Z").toEpochMilli()
    private val start = LocalDate.of(2026, 1, 1).toEpochDay()
    private val end = LocalDate.of(2026, 12, 31).toEpochDay()
    private val expense = Expense("gym", "健身年卡", 120000, "fitness", start, "学校附近", ExpenseType.MULTI_USE, now, now)
    private val card = MultiUseExpense("gym", MultiUseType.UNLIMITED, start, end, null, 5000)
    private val usage = UsageRecord("use-1", "gym", now - 60_000, "练背", now)

    private fun snapshot() = LedgerSnapshot(
        listOf(Category("fitness", "健身", true, now)), listOf(expense), listOf(card),
        listOf(usage), LedgerDefaults.settings,
    )

    private fun validate(snapshot: LedgerSnapshot) = LedgerValidation.validateSnapshot(snapshot, now, zone)
    private fun rejects(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java) { block() }

    @Test fun allPersistentFieldsAndRelationsCanBeValidatedTogether() {
        validate(snapshot())
        val details = ExpenseDetails(expense, card, listOf(usage))
        assertEquals(1, details.usedCount)
        assertEquals(0, details.copy(usages = emptyList()).usedCount)
    }

    @Test fun duplicateIdsAreRejectedForEveryTable() {
        val original = snapshot()
        rejects { validate(original.copy(categories = original.categories + original.categories)) }
        rejects { validate(original.copy(expenses = original.expenses + original.expenses)) }
        rejects { validate(original.copy(multiUse = original.multiUse + original.multiUse)) }
        rejects { validate(original.copy(usageRecords = original.usageRecords + original.usageRecords)) }
        rejects { validate(original.copy(settings = original.settings + original.settings)) }
    }

    @Test fun danglingForeignKeysCannotBeRestored() {
        rejects { validate(snapshot().copy(expenses = listOf(expense.copy(categoryId = "missing")))) }
        rejects { validate(snapshot().copy(multiUse = listOf(card.copy(expenseId = "missing")))) }
        rejects { validate(snapshot().copy(usageRecords = listOf(usage.copy(expenseId = "missing")))) }
    }

    @Test fun invalidTypesAndMissingMultiUseDetailsAreRejected() {
        rejects { validate(snapshot().copy(expenses = listOf(expense.copy(type = "UNKNOWN")))) }
        rejects { validate(snapshot().copy(multiUse = emptyList())) }
        rejects { validate(snapshot().copy(multiUse = listOf(card.copy(multiUseType = "UNKNOWN")))) }
        rejects { validate(snapshot().copy(expenses = listOf(expense.copy(type = ExpenseType.SINGLE)))) }
    }

    @Test fun reducingTotalBelowUsageHistoryIsRejected() {
        val records = (1..12).map { usage.copy(id = "use-$it") }
        LedgerValidation.validateExpense(expense, card.copy(multiUseType = MultiUseType.LIMITED, totalUses = 12), records, now, zone)
        rejects {
            LedgerValidation.validateExpense(expense, card.copy(multiUseType = MultiUseType.LIMITED, totalUses = 11), records, now, zone)
        }
    }

    @Test fun totalUsesMustMatchCardType() {
        listOf<Int?>(null, 0, -1, 1_000_001).forEach { total ->
            rejects { LedgerValidation.validateExpense(expense, card.copy(multiUseType = MultiUseType.LIMITED, totalUses = total)) }
        }
        rejects { LedgerValidation.validateExpense(expense, card.copy(totalUses = 5)) }
    }

    @Test fun brokenAmountAndDateFieldsAreRejectedBeforePersistence() {
        listOf(0L, -1L, Long.MAX_VALUE).forEach { amount ->
            rejects { validate(snapshot().copy(expenses = listOf(expense.copy(amountCents = amount)))) }
        }
        rejects { validate(snapshot().copy(multiUse = listOf(card.copy(referenceSinglePriceCents = 0)))) }
        rejects { validate(snapshot().copy(multiUse = listOf(card.copy(endDate = start - 1)))) }
        rejects { validate(snapshot().copy(expenses = listOf(expense.copy(purchaseDate = Long.MAX_VALUE)))) }
        rejects { validate(snapshot().copy(expenses = listOf(expense.copy(createdAt = Long.MIN_VALUE)))) }
    }

    @Test fun purchaseDateCanBePastOrFuture() {
        LedgerValidation.validateExpense(expense.copy(purchaseDate = end), card, listOf(usage), now, zone)
        LedgerValidation.validateExpense(expense.copy(purchaseDate = start - 365), card, listOf(usage), now, zone)
    }

    @Test fun futureUsageIsRejectedEvenWhenCardValidityAllowsIt() {
        rejects { LedgerValidation.validateUsage(usage.copy(usageDate = now + 1), card, now, zone) }
    }

    @Test fun expiredCardAllowsHistoricalEntryInsideInclusiveValidity() {
        val expiredEnd = LocalDate.of(2026, 8, 31).toEpochDay()
        val expired = card.copy(endDate = expiredEnd)
        val onLastDay = LocalDate.ofEpochDay(expiredEnd).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        LedgerValidation.validateUsage(usage.copy(usageDate = onLastDay), expired, now, zone)
        rejects { LedgerValidation.validateUsage(usage, expired, now, zone) }
    }

    @Test fun localDatesAreUsedForValidityAtMidnight() {
        val exactStart = LocalDate.ofEpochDay(start).atStartOfDay(zone).toInstant().toEpochMilli()
        LedgerValidation.validateUsage(usage.copy(usageDate = exactStart), card, now, zone)
        rejects { LedgerValidation.validateUsage(usage.copy(usageDate = exactStart - 1), card, now, zone) }
    }

    @Test fun changingValidityCannotStrandExistingHistory() {
        val tomorrow = LocalDate.of(2026, 9, 14).toEpochDay()
        rejects {
            LedgerValidation.validateExpense(
                expense, card.copy(startDate = tomorrow), listOf(usage), now, zone,
                checkExistingUsageDates = true,
            )
        }
    }

    @Test fun backupRemainsValidAfterCrossingTimeZonesAndRestoringOnASlowClock() {
        val midnightInShanghai = LocalDate.ofEpochDay(start).atStartOfDay(zone).toInstant().toEpochMilli()
        val recordedInShanghai = usage.copy(usageDate = midnightInShanghai)
        LedgerValidation.validateUsage(recordedInShanghai, card, now, zone)
        // This instant is the previous calendar day in UTC. The old phone already
        // validated it, and clock skew on the new phone must not destroy portability.
        val restored = snapshot().copy(usageRecords = listOf(recordedInShanghai))
        LedgerValidation.validateSnapshot(restored, midnightInShanghai - 60_000, ZoneId.of("UTC"))
        LedgerValidation.validateSnapshot(restored, midnightInShanghai - 60_000, ZoneId.of("America/Los_Angeles"))
        assertEquals(midnightInShanghai, restored.usageRecords.single().usageDate)
    }

    @Test fun unrelatedExpenseEditsPreserveHistoryAfterTravelOrClockChange() {
        val midnightInShanghai = LocalDate.ofEpochDay(start).atStartOfDay(zone).toInstant().toEpochMilli()
        val recordedInShanghai = usage.copy(usageDate = midnightInShanghai)
        LedgerValidation.validateExpense(
            expense.copy(name = "学校健身年卡", amountCents = 125000), card,
            listOf(recordedInShanghai), midnightInShanghai - 60_000, ZoneId.of("UTC"),
        )
    }

    @Test fun portableSnapshotStillRejectsInvalidStoredUsageTimestamps() {
        rejects { validate(snapshot().copy(usageRecords = listOf(usage.copy(usageDate = Long.MAX_VALUE)))) }
        rejects { validate(snapshot().copy(usageRecords = listOf(usage.copy(createdAt = Long.MIN_VALUE)))) }
    }

    @Test fun requiredSettingsAreValidatedAndUnknownFutureSettingsSurvive() {
        rejects { validate(snapshot().copy(settings = emptyList())) }
        rejects { validate(snapshot().copy(settings = listOf(AppSetting("theme", "invalid"), AppSetting("currency", "CNY")))) }
        rejects { validate(snapshot().copy(settings = listOf(AppSetting("theme", "SYSTEM"), AppSetting("currency", "USD")))) }
        validate(snapshot().copy(settings = LedgerDefaults.settings + AppSetting("future.preference", "value")))
    }

    @Test fun emptyLedgerStillHasCategoriesAndSettings() {
        validate(snapshot().copy(expenses = emptyList(), multiUse = emptyList(), usageRecords = emptyList()))
        rejects { validate(snapshot().copy(categories = emptyList())) }
    }
}
