package cn.student.expensetracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {
    @Test fun decimalInputStaysExactInCents() {
        assertEquals(1234L, Money.parseCents("12.34"))
        assertEquals(30L, Money.parseCents("0.1") + Money.parseCents("0.2"))
        assertEquals("0.30", Money.format(30))
        assertEquals("-3.05", Money.format(-305))
        assertEquals(Money.MAX_AMOUNT_CENTS, Money.parseCents("999999999.99"))
    }

    @Test fun invalidAmountsNeverSilentlyRoundOrOverflow() {
        listOf("", " ", "0", "-1", "1.001", "1e3", "NaN", "1,000", "1000000000", "999999999999999999999999999999999999").forEach {
            assertThrows("Should reject $it", IllegalArgumentException::class.java) { Money.parseCents(it) }
        }
    }

    @Test fun annualCardCostsMatchAcceptanceExamples() {
        assertNull(Money.averageCostCents(120000, 0))
        assertEquals(12000L, Money.averageCostCents(120000, 10))
        assertEquals(4000L, Money.averageCostCents(120000, 30))
        assertEquals(2400L, Money.averageCostCents(120000, 50))
        assertEquals(2264L, Money.averageCostCents(120000, 53))
    }

    @Test fun fixedCardDistinguishesActualAndTheoreticalCost() {
        assertEquals(5000L, Money.averageCostCents(60000, 12))
        assertEquals(2000L, Money.averageCostCents(60000, 30))
        // Undoing one use derives a fresh average from the remaining history.
        assertEquals(5455L, Money.averageCostCents(60000, 11))
    }

    @Test fun halfCentRoundingIsExplicitAndConsistent() {
        assertEquals(1L, Money.averageCostCents(1, 2))
        assertEquals(3L, Money.averageCostCents(10, 4))
    }

    @Test fun breakEvenRoundsUpWithoutFloatingPoint() {
        assertNull(Money.breakEvenUses(120000, null))
        assertEquals(24L, Money.breakEvenUses(120000, 5000))
        assertEquals(25L, Money.breakEvenUses(120001, 5000))
        assertEquals(1L, Money.breakEvenUses(100, 200))
        assertEquals(Long.MAX_VALUE, Money.breakEvenUses(Long.MAX_VALUE, 1))
    }

    @Test fun savingsReflectActualHistory() {
        assertEquals(-20000L, Money.theoreticalSavingsCents(120000, 20, 5000))
        assertEquals(0L, Money.theoreticalSavingsCents(120000, 24, 5000))
        assertEquals(30000L, Money.theoreticalSavingsCents(120000, 30, 5000))
        assertThrows(ArithmeticException::class.java) { Money.theoreticalSavingsCents(1, 2, Long.MAX_VALUE) }
    }
}
