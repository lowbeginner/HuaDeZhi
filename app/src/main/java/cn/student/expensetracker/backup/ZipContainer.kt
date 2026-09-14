package cn.student.expensetracker.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Strict, bounded two-file ZIP container. Nothing is ever extracted onto the filesystem. */
internal object ZipContainer {
    const val MAX_DATABASE_BYTES = 32 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_TOTAL_BYTES = MAX_DATABASE_BYTES + MAX_MANIFEST_BYTES
    // Includes ZIP overhead, even for nearly incompressible database.json data.
    private const val MAX_ARCHIVE_BYTES = 34 * 1024 * 1024
    private val allowedNames = setOf(BackupCodec.MANIFEST, BackupCodec.DATABASE)

    fun write(files: Map<String, ByteArray>, output: OutputStream) {
        val nonClosing = object : FilterOutputStream(output) {
            override fun close() = flush()
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
        }
        ZipOutputStream(nonClosing, Charsets.UTF_8).use { zip ->
            files.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
            zip.finish()
        }
    }

    fun read(input: InputStream): Map<String, ByteArray> {
        val archive = readBounded(input, MAX_ARCHIVE_BYTES)
        val expected = verifyDirectory(archive)
        val result = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(archive), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || entry.name !in allowedNames || entry.name in result || result.size >= 2) {
                    throw BackupException("备份包含未知文件或重复文件，格式不正确。")
                }
                val limit = if (entry.name == BackupCodec.MANIFEST) MAX_MANIFEST_BYTES else MAX_DATABASE_BYTES
                val bytes = readBounded(zip, limit)
                totalBytes += bytes.size
                if (totalBytes > MAX_TOTAL_BYTES) throw BackupException("备份解压后的数据过大。")
                val centralEntry = expected.getValue(entry.name)
                if (bytes.isEmpty() || bytes.size.toLong() != centralEntry.size || entry.crc != centralEntry.crc) {
                    throw BackupException(BackupCodec.CORRUPT)
                }
                result[entry.name] = bytes
                zip.closeEntry()
            }
        }
        if (result.keys != allowedNames) throw BackupException("备份缺少 manifest.json 或 database.json。")
        return result
    }

    /** ZipInputStream alone accepts missing/truncated central directories; check both structures. */
    private fun verifyDirectory(bytes: ByteArray): Map<String, DirectoryEntry> {
        if (bytes.size < 22) throw BackupException(BackupCodec.CORRUPT)
        val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65557)).firstOrNull { offset ->
            u32(bytes, offset) == 0x06054b50L && offset + 22 + u16(bytes, offset + 20) == bytes.size
        } ?: throw BackupException("备份 ZIP 文件不完整，缺少有效的结束记录。")
        val count = u16(bytes, end + 10)
        if (u16(bytes, end + 4) != 0 || u16(bytes, end + 6) != 0 ||
            u16(bytes, end + 8) != count || count != 2
        ) throw BackupException("备份 ZIP 文件数量或分卷格式不正确。")
        val centralSize = u32(bytes, end + 12)
        val centralStart = u32(bytes, end + 16)
        if (centralStart + centralSize != end.toLong() || centralSize < 92 || centralStart > Int.MAX_VALUE) {
            throw BackupException(BackupCodec.CORRUPT)
        }
        var cursor = centralStart.toInt()
        var uncompressedTotal = 0L
        val entries = linkedMapOf<String, DirectoryEntry>()
        repeat(count) {
            if (cursor.toLong() + 46 > end || u32(bytes, cursor) != 0x02014b50L) {
                throw BackupException(BackupCodec.CORRUPT)
            }
            val flags = u16(bytes, cursor + 8)
            val method = u16(bytes, cursor + 10)
            // UTF-8 and streaming descriptors are supported; encryption and ZIP64 are not v1.
            if ((flags and 0xF7F7) != 0 || method !in setOf(ZipEntry.DEFLATED, ZipEntry.STORED) ||
                u16(bytes, cursor + 34) != 0
            ) throw BackupException("备份使用了当前版本不支持的 ZIP 格式。")
            val nameLength = u16(bytes, cursor + 28)
            val extraLength = u16(bytes, cursor + 30)
            val commentLength = u16(bytes, cursor + 32)
            val next = cursor.toLong() + 46 + nameLength + extraLength + commentLength
            if (next > end) throw BackupException(BackupCodec.CORRUPT)
            val name = String(bytes, cursor + 46, nameLength, Charsets.UTF_8)
            if (name !in allowedNames || name in entries) {
                throw BackupException("备份包含未知文件或重复文件，格式不正确。")
            }
            val size = u32(bytes, cursor + 24)
            val compressedSize = u32(bytes, cursor + 20)
            val limit = if (name == BackupCodec.MANIFEST) MAX_MANIFEST_BYTES else MAX_DATABASE_BYTES
            uncompressedTotal += size
            if (size !in 1..limit.toLong() || uncompressedTotal > MAX_TOTAL_BYTES || compressedSize > MAX_ARCHIVE_BYTES) {
                throw BackupException("备份文件为空或超过当前版本的数据大小限制。")
            }
            entries[name] = DirectoryEntry(
                name, size, compressedSize, u32(bytes, cursor + 16),
                u32(bytes, cursor + 42), flags, method,
            )
            cursor = next.toInt()
        }
        if (cursor != end || entries.keys != allowedNames) throw BackupException(BackupCodec.CORRUPT)
        val ordered = entries.values.sortedBy { it.localOffset }
        if (ordered.first().localOffset != 0L) throw BackupException(BackupCodec.CORRUPT)
        ordered.forEachIndexed { index, entry ->
            val boundary = ordered.getOrNull(index + 1)?.localOffset ?: centralStart
            verifyLocalHeader(bytes, entry, boundary)
        }
        return entries
    }

    private fun verifyLocalHeader(bytes: ByteArray, entry: DirectoryEntry, boundary: Long) {
        val offset = entry.localOffset
        if (offset < 0 || offset + 30 > boundary || boundary > bytes.size) throw BackupException(BackupCodec.CORRUPT)
        val start = offset.toInt()
        if (u32(bytes, start) != 0x04034b50L || u16(bytes, start + 6) != entry.flags ||
            u16(bytes, start + 8) != entry.method
        ) throw BackupException(BackupCodec.CORRUPT)
        val nameLength = u16(bytes, start + 26)
        val extraLength = u16(bytes, start + 28)
        val dataStart = offset + 30 + nameLength + extraLength
        val dataEnd = dataStart + entry.compressedSize
        if (dataStart > boundary || dataEnd > boundary ||
            String(bytes, start + 30, nameLength, Charsets.UTF_8) != entry.name
        ) throw BackupException(BackupCodec.CORRUPT)
        if ((entry.flags and 8) == 0) {
            if (dataEnd != boundary || u32(bytes, start + 14) != entry.crc ||
                u32(bytes, start + 18) != entry.compressedSize || u32(bytes, start + 22) != entry.size
            ) throw BackupException(BackupCodec.CORRUPT)
        } else {
            val descriptorLength = boundary - dataEnd
            if (descriptorLength != 12L && descriptorLength != 16L) throw BackupException(BackupCodec.CORRUPT)
            var descriptor = dataEnd.toInt()
            if (descriptorLength == 16L) {
                if (u32(bytes, descriptor) != 0x08074b50L) throw BackupException(BackupCodec.CORRUPT)
                descriptor += 4
            }
            if (u32(bytes, descriptor) != entry.crc || u32(bytes, descriptor + 4) != entry.compressedSize ||
                u32(bytes, descriptor + 8) != entry.size
            ) throw BackupException(BackupCodec.CORRUPT)
        }
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(8192)
        var count = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            if (read == 0) {
                val one = input.read()
                if (one == -1) break
                if (++count > limit) throw BackupException("备份超过当前版本的数据大小限制。")
                output.write(one)
            } else {
                count += read
                if (count > limit) throw BackupException("备份超过当前版本的数据大小限制。")
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset.toLong() + 2 > bytes.size) throw BackupException(BackupCodec.CORRUPT)
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)

    private data class DirectoryEntry(
        val name: String,
        val size: Long,
        val compressedSize: Long,
        val crc: Long,
        val localOffset: Long,
        val flags: Int,
        val method: Int,
    )
}
