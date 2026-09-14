package cn.student.expensetracker.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** Monetary amounts are integer cents; rounding happens only for displayed costs. */
object Money {
    const val MAX_AMOUNT_CENTS = 99_999_999_999L

    fun parseCents(text: String): Long {
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "请输入金额" }
        require(normalized.length <= 32 && normalized.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) {
            "金额请输入正数，最多保留两位小数"
        }
        val decimal = normalized.toBigDecimal()
        require(decimal > BigDecimal.ZERO) { "金额必须大于 0" }
        require(decimal <= BigDecimal.valueOf(MAX_AMOUNT_CENTS, 2)) { "金额过大，最多支持 999999999.99 元" }
        return decimal.movePointRight(2).longValueExact()
    }

    fun format(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    fun averageCostCents(totalCents: Long, usedCount: Int): Long? {
        require(totalCents >= 0) { "金额不能为负数" }
        require(usedCount >= 0) { "使用次数不能为负数" }
        if (usedCount == 0) return null
        return BigDecimal.valueOf(totalCents)
            .divide(BigDecimal.valueOf(usedCount.toLong()), 0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    /** Round UP: a fractional visit never means the purchase has broken even. */
    fun breakEvenUses(totalCents: Long, referencePriceCents: Long?): Long? {
        require(totalCents > 0) { "金额必须大于 0" }
        if (referencePriceCents == null) return null
        require(referencePriceCents > 0) { "参考单次价格必须大于 0" }
        return totalCents / referencePriceCents + if (totalCents % referencePriceCents == 0L) 0 else 1
    }

    fun theoreticalSavingsCents(totalCents: Long, usedCount: Int, referencePriceCents: Long): Long {
        require(totalCents >= 0 && usedCount >= 0 && referencePriceCents > 0) { "回本计算参数不正确" }
        return Math.subtractExact(Math.multiplyExact(usedCount.toLong(), referencePriceCents), totalCents)
    }
}
