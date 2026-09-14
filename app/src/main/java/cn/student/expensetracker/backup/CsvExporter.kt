package cn.student.expensetracker.backup

import cn.student.expensetracker.data.ExpenseType
import cn.student.expensetracker.data.LedgerSnapshot
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.time.LocalDate

/** A readable payment report, not a restorable backup. Usage records never add payments. */
object CsvExporter {
    /** UTF-8 BOM for Excel, CRLF records and RFC 4180 quoting. Does not close the stream. */
    fun write(snapshot: LedgerSnapshot, output: OutputStream) {
        val categories = snapshot.categories.associate { it.id to it.name }
        val writer = OutputStreamWriter(output, Charsets.UTF_8)
        writer.write('\uFEFF'.code)
        writer.write("日期,名称,分类,金额（元）,类型,备注\r\n")
        snapshot.expenses.sortedWith(compareByDescending<cn.student.expensetracker.data.Expense> {
            it.purchaseDate
        }.thenBy { it.id }).forEach { expense ->
            val row = listOf(
                LocalDate.ofEpochDay(expense.purchaseDate).toString(),
                safeText(expense.name),
                safeText(categories[expense.categoryId] ?: "未知分类"),
                BigDecimal.valueOf(expense.amountCents, 2).toPlainString(),
                if (expense.type == ExpenseType.SINGLE) "单次消费" else "多次消费",
                safeText(expense.note),
            )
            writer.write(row.joinToString(",") { quote(it) })
            writer.write("\r\n")
        }
        writer.flush()
    }

    /** Quoting alone does not prevent Excel from evaluating untrusted formula-like text. */
    private fun safeText(value: String): String {
        val first = value.dropWhile { it.isWhitespace() || it == '\uFEFF' }.firstOrNull()
        return if (first in listOf('=', '+', '-', '@') || value.firstOrNull() in listOf('\t', '\r', '\n')) {
            "'" + value
        } else value
    }

    private fun quote(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
