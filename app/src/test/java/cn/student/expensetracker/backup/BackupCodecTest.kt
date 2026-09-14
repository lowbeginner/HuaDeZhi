package cn.student.expensetracker.backup

import cn.student.expensetracker.data.AppSetting
import cn.student.expensetracker.data.Category
import cn.student.expensetracker.data.Expense
import cn.student.expensetracker.data.ExpenseType
import cn.student.expensetracker.data.LedgerSnapshot
import cn.student.expensetracker.data.MultiUseExpense
import cn.student.expensetracker.data.MultiUseType
import cn.student.expensetracker.data.UsageRecord
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupCodecTest {
    private val now = Instant.parse("2026-09-13T12:00:00Z").toEpochMilli()
    private val codec = BackupCodec { now }
    private val json = Json { explicitNulls = true; encodeDefaults = true }

    @Test fun completeUserFixtureRoundTripsEveryPersistentField() {
        val original = fixture()
        val prepared = codec.read(ByteArrayInputStream(export(original)))
        assertEquals(original, prepared.snapshot)
        assertEquals(now, prepared.manifest.exportTime)
        assertEquals(BackupCodec.FORMAT, prepared.manifest.format)
        assertEquals(1, prepared.manifest.backupVersion)
        assertEquals(1, prepared.manifest.databaseVersion)
        assertEquals(5, prepared.manifest.tableCounts.expenses)
        assertEquals(65, prepared.manifest.tableCounts.usageRecords)
        assertEquals(79L, prepared.manifest.recordCount)
        assertEquals(186200L, prepared.snapshot.expenses.sumOf { it.amountCents })

        val gymUses = prepared.snapshot.usageRecords.count { it.expenseId == "gym" }
        val swimUses = prepared.snapshot.usageRecords.count { it.expenseId == "swim" }
        assertEquals(53, gymUses)
        assertEquals(12, swimUses)
        assertEquals("22.64", cost(120000, gymUses))
        assertEquals("50.00", cost(60000, swimUses))
        assertEquals("20.00", cost(60000, prepared.snapshot.multiUse.single { it.expenseId == "swim" }.totalUses!!))
        assertEquals(18, 30 - swimUses)
        assertEquals(5000L, prepared.snapshot.multiUse.single { it.expenseId == "gym" }.referenceSinglePriceCents)
        assertEquals("DARK", prepared.snapshot.settings.single { it.key == "theme" }.value)
        assertEquals("私人分类", prepared.snapshot.categories.single { it.id == "custom" }.name)
    }

    @Test fun hashMismatchRejectsModifiedDatabase() {
        val files = files(export())
        files[BackupCodec.DATABASE] = files.getValue(BackupCodec.DATABASE).toString(Charsets.UTF_8)
            .replace("晚饭", "午饭").toByteArray()
        assertRejected(archive(files), "校验失败")
    }

    @Test fun missingChecksumCannotDisableIntegrityVerification() {
        val files = files(export())
        val text = files.getValue(BackupCodec.MANIFEST).toString(Charsets.UTF_8)
        files[BackupCodec.MANIFEST] = text.replace(Regex(",\"sha256\":\"[a-f0-9]+\""), "").toByteArray()
        assertRejected(archive(files), "必要字段")
        files[BackupCodec.MANIFEST] = text.replace(Regex("\"sha256\":\"[a-f0-9]+\""), "\"sha256\":\"\"").toByteArray()
        assertRejected(archive(files), "SHA-256")
    }

    @Test fun missingNullableFieldAndMissingCollectionAreRejected() {
        val files = files(export())
        val text = files.getValue(BackupCodec.DATABASE).toString(Charsets.UTF_8)
        files[BackupCodec.DATABASE] = text.replace("\"totalUses\":null,", "").toByteArray()
        updateHash(files)
        assertRejected(archive(files), "必要字段")

        val incomplete = "{\"categories\":[],\"expenses\":[],\"multiUse\":[],\"settings\":[]}"
        files[BackupCodec.DATABASE] = incomplete.toByteArray()
        updateHash(files)
        assertRejected(archive(files), "必要字段")
    }

    @Test fun futureAndOldVersionsHaveExplicitCompatibilityErrors() {
        listOf(0 to 1, 2 to 1, 1 to 2).forEach { (backupVersion, databaseVersion) ->
            val files = files(export())
            val manifest = manifest(files).copy(backupVersion = backupVersion, databaseVersion = databaseVersion)
            files[BackupCodec.MANIFEST] = json.encodeToString(manifest).toByteArray()
            assertRejected(archive(files), "不兼容版本")
        }
    }

    @Test fun invalidVersionShapeAndInvalidUtf8HaveReadableErrors() {
        val files = files(export())
        files[BackupCodec.MANIFEST] = files.getValue(BackupCodec.MANIFEST).toString(Charsets.UTF_8)
            .replace("\"backupVersion\":1", "\"backupVersion\":{}").toByteArray()
        assertRejected(archive(files), "版本信息")
        files[BackupCodec.MANIFEST] = byteArrayOf(0xc3.toByte(), 0x28)
        assertRejected(archive(files), "无法读取")
    }

    @Test fun manifestCountsMustMatchActualTables() {
        val files = files(export())
        val manifest = manifest(files)
        files[BackupCodec.MANIFEST] = json.encodeToString(manifest.copy(recordCount = manifest.recordCount - 1)).toByteArray()
        assertRejected(archive(files), "数量不一致")
        files[BackupCodec.MANIFEST] = json.encodeToString(
            manifest.copy(tableCounts = manifest.tableCounts.copy(usageRecords = 64)),
        ).toByteArray()
        assertRejected(archive(files), "数量不一致")
    }

    @Test fun unrelatedFilesAndMalformedJsonAreRejected() {
        assertRejected(ByteArray(0))
        assertRejected("这是一份普通文本文件".toByteArray())
        val files = files(export())
        files[BackupCodec.DATABASE] = "{invalid json".toByteArray()
        updateHash(files)
        assertRejected(archive(files), "JSON")
    }

    @Test fun missingFilesUnexpectedPathsAndDuplicateEntriesAreRejected() {
        val files = files(export())
        assertRejected(archive(mapOf(BackupCodec.DATABASE to files.getValue(BackupCodec.DATABASE))))
        assertRejected(archive(files + ("../surprise.txt" to "bad".toByteArray())))
        // Names have equal UTF-8 length: replace both local and directory names without altering offsets.
        val duplicate = replaceBytes(export(), "database.json".toByteArray(), "manifest.json".toByteArray())
        assertRejected(duplicate, "重复文件")
    }

    @Test fun payloadAndDirectoryTruncationAreBothRejected() {
        val complete = export()
        assertRejected(complete.copyOf(complete.size / 2))
        assertRejected(complete.copyOf(complete.size - 1))
        assertRejected(complete.copyOf(complete.size - 22), "不完整")
        // Appending bytes must not turn a corrupted ZIP into an accepted backup.
        assertRejected(complete + byteArrayOf(1, 2, 3))
    }

    @Test fun duplicateJsonKeysAndExcessiveNestingAreRejected() {
        val files = files(export())
        files[BackupCodec.MANIFEST] = files.getValue(BackupCodec.MANIFEST).toString(Charsets.UTF_8)
            .replace("\"backupVersion\":1", "\"backupVersion\":1,\"backupVersion\":1").toByteArray()
        assertRejected(archive(files), "重复字段")
        files[BackupCodec.MANIFEST] = ("[".repeat(30) + "0" + "]".repeat(30)).toByteArray()
        assertRejected(archive(files), "嵌套过深")
    }

    @Test fun damagedForeignKeysAreRejectedBeforeRestore() {
        val base = fixture()
        assertRejected(unchecked(base.copy(expenses = base.expenses.map {
            if (it.id == "dinner") it.copy(categoryId = "missing") else it
        })), "不存在的分类")
        assertRejected(unchecked(base.copy(usageRecords = base.usageRecords.mapIndexed { index, usage ->
            if (index == 0) usage.copy(expenseId = "missing") else usage
        })), "对应的多次消费")
        assertRejected(unchecked(base.copy(multiUse = base.multiUse.map {
            if (it.expenseId == "gym") it.copy(expenseId = "missing") else it
        })), "对应的消费记录")
    }

    @Test fun duplicateIdsAndOverusedLimitedCardsAreRejected() {
        val base = fixture()
        assertRejected(unchecked(base.copy(usageRecords = base.usageRecords + base.usageRecords.first())), "重复 ID")
        assertRejected(unchecked(base.copy(categories = base.categories + base.categories.first())), "重复 ID")
        assertRejected(unchecked(base.copy(expenses = base.expenses + base.expenses.first())), "重复 ID")
        assertRejected(unchecked(base.copy(multiUse = base.multiUse + base.multiUse.first())), "重复 ID")
        assertRejected(unchecked(base.copy(settings = base.settings + base.settings.first())), "重复 ID")
        assertRejected(unchecked(base.copy(multiUse = base.multiUse.map {
            if (it.expenseId == "swim") it.copy(totalUses = 11) else it
        })), "总次数不能小于")
    }

    @Test fun inflatedZipSizeIsRejectedWithoutAllocatingTheClaimedSize() {
        val bytes = export()
        val central = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val index = findBytes(bytes, central)
        assertTrue(index >= 0)
        // First central entry's uncompressed size becomes 2 GiB - 1.
        bytes[index + 24] = 0xff.toByte()
        bytes[index + 25] = 0xff.toByte()
        bytes[index + 26] = 0xff.toByte()
        bytes[index + 27] = 0x7f
        assertRejected(bytes, "大小限制")
    }

    @Test fun oversizedManifestCannotDecompressUnboundedly() {
        val files = files(export())
        files[BackupCodec.MANIFEST] = ByteArray(65537) { ' '.code.toByte() }
        assertRejected(archive(files), "大小限制")
    }

    @Test fun cancellationPropagatesUnchangedAndStreamsRemainCallerOwned() {
        val cancellation = CancellationException("cancel test")
        val input = object : InputStream() { override fun read(): Int = throw cancellation }
        try {
            codec.read(input)
            throw AssertionError("Cancellation should propagate")
        } catch (caught: CancellationException) {
            assertTrue(caught === cancellation)
        }
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        codec.write(fixture(), output)
        assertFalse(closed)
        val readInput = object : ByteArrayInputStream(output.toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        codec.read(readInput)
        assertFalse(closed)
    }

    @Test fun csvExportsPaymentsOnceWithBomAndEscapedUntrustedText() {
        val base = fixture()
        val modified = base.copy(expenses = base.expenses.map {
            if (it.id == "dinner") it.copy(name = "=HYPERLINK(\"https://example.invalid\")", note = "  +2\n第二行,\"引号\"") else it
        })
        val output = ByteArrayOutputStream()
        CsvExporter.write(modified, output)
        val bytes = output.toByteArray()
        assertEquals(listOf(0xef, 0xbb, 0xbf), bytes.take(3).map { it.toInt() and 0xff })
        val rows = parseCsv(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        assertEquals(6, rows.size)
        assertTrue(rows.all { it.size == 6 })
        val data = rows.drop(1)
        assertEquals(BigDecimal("1862.00"), data.map { it[3].toBigDecimal() }.reduce(BigDecimal::add))
        assertEquals(2, data.count { it[4] == "多次消费" })
        val dinner = data.single { "HYPERLINK" in it[1] }
        assertEquals("'=HYPERLINK(\"https://example.invalid\")", dinner[1])
        assertEquals("'  +2\n第二行,\"引号\"", dinner[5])
    }

    @Test fun csvNeutralizesAllCommonFormulaPrefixesInNamesCategoriesAndNotes() {
        listOf("=1", "+1", "-1", "@SUM(A1)", "\t=1", "\r=1", "\n=1", " \t+1").forEach { dangerous ->
            val base = fixture()
            val snapshot = base.copy(
                expenses = listOf(base.expenses.first().copy(name = dangerous, note = dangerous)),
                categories = base.categories.map { it.copy(name = dangerous) },
            )
            val output = ByteArrayOutputStream()
            CsvExporter.write(snapshot, output)
            val data = parseCsv(output.toString("UTF-8").removePrefix("\uFEFF"))[1]
            listOf(1, 2, 5).forEach { assertEquals("'" + dangerous, data[it]) }
        }
    }

    private fun fixture(): LedgerSnapshot {
        val created = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()
        fun day(value: String) = LocalDate.parse(value).toEpochDay()
        fun expense(id: String, name: String, amount: Long, category: String, date: String, type: String) =
            Expense(id, name, amount, category, day(date), "备注：$name", type, created, now)
        val usages = (1..53).map { number ->
            UsageRecord("gym-$number", "gym", created + number * 86400000L, "训练 $number", now)
        } + (1..12).map { number ->
            UsageRecord("swim-$number", "swim", Instant.parse("2026-08-01T12:00:00Z").toEpochMilli() + number * 86400000L, "游泳 $number", now)
        }
        return LedgerSnapshot(
            categories = listOf(
                Category("food", "餐饮", true, created), Category("transport", "交通", true, created),
                Category("fitness", "健身", true, created), Category("custom", "私人分类", false, created),
            ),
            expenses = listOf(
                expense("dinner", "晚饭", 2800, "food", "2026-09-13", ExpenseType.SINGLE),
                expense("taxi", "打车", 1600, "transport", "2026-09-12", ExpenseType.SINGLE),
                expense("coffee", "咖啡", 1800, "custom", "2026-09-12", ExpenseType.SINGLE),
                expense("gym", "健身年卡", 120000, "fitness", "2026-01-01", ExpenseType.MULTI_USE),
                expense("swim", "游泳卡", 60000, "fitness", "2026-07-31", ExpenseType.MULTI_USE),
            ),
            multiUse = listOf(
                MultiUseExpense("gym", MultiUseType.UNLIMITED, day("2026-01-01"), day("2026-12-31"), null, 5000),
                MultiUseExpense("swim", MultiUseType.LIMITED, day("2026-08-01"), null, 30, null),
            ),
            usageRecords = usages,
            settings = listOf(AppSetting("theme", "DARK"), AppSetting("currency", "CNY"), AppSetting("homePeriod", "MONTH")),
        )
    }

    private fun cost(amountCents: Long, count: Int): String = BigDecimal.valueOf(amountCents, 2)
        .divide(BigDecimal(count), 2, RoundingMode.HALF_UP).toPlainString()

    private fun export(snapshot: LedgerSnapshot = fixture()): ByteArray = ByteArrayOutputStream().also {
        codec.write(snapshot, it)
    }.toByteArray()

    private fun files(bytes: ByteArray): MutableMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    private fun archive(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun manifest(files: Map<String, ByteArray>): BackupManifest =
        json.decodeFromString(files.getValue(BackupCodec.MANIFEST).toString(Charsets.UTF_8))

    private fun updateHash(files: MutableMap<String, ByteArray>) {
        files[BackupCodec.MANIFEST] = json.encodeToString(
            manifest(files).copy(sha256 = BackupCodec.sha256(files.getValue(BackupCodec.DATABASE))),
        ).toByteArray()
    }

    private fun unchecked(snapshot: LedgerSnapshot): ByteArray {
        val files = files(export())
        files[BackupCodec.DATABASE] = json.encodeToString(snapshot).toByteArray()
        val counts = BackupRecordCounts.from(snapshot)
        files[BackupCodec.MANIFEST] = json.encodeToString(manifest(files).copy(
            recordCount = counts.total, tableCounts = counts,
            sha256 = BackupCodec.sha256(files.getValue(BackupCodec.DATABASE)),
        )).toByteArray()
        return archive(files)
    }

    private fun assertRejected(bytes: ByteArray, messagePart: String? = null) {
        try {
            codec.read(ByteArrayInputStream(bytes))
            throw AssertionError("Invalid backup was accepted")
        } catch (error: BackupException) {
            if (messagePart != null) assertTrue("Expected '$messagePart' in '${error.message}'", error.message.orEmpty().contains(messagePart))
        }
    }

    private fun findBytes(bytes: ByteArray, needle: ByteArray, start: Int = 0): Int =
        (start..bytes.size - needle.size).firstOrNull { offset ->
            needle.indices.all { bytes[offset + it] == needle[it] }
        } ?: -1

    private fun replaceBytes(bytes: ByteArray, before: ByteArray, after: ByteArray): ByteArray {
        require(before.size == after.size)
        val result = bytes.copyOf()
        var start = 0
        while (true) {
            val index = findBytes(result, before, start)
            if (index < 0) return result
            after.copyInto(result, index)
            start = index + after.size
        }
    }

    /** Independent small RFC 4180 reader verifies exported cell values, including embedded LF. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && text.getOrNull(index + 1) == '"' -> { field.append('"'); index++ }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { row += field.toString(); field.clear() }
                char == '\r' && !quoted && text.getOrNull(index + 1) == '\n' -> {
                    row += field.toString(); field.clear(); rows += row.toList(); row.clear(); index++
                }
                else -> field.append(char)
            }
            index++
        }
        assertFalse("CSV quote was left unclosed", quoted)
        assertTrue("CSV must end in CRLF", row.isEmpty() && field.isEmpty())
        return rows
    }
}
