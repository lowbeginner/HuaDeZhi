package cn.student.expensetracker.backup

import cn.student.expensetracker.data.LedgerSnapshot
import cn.student.expensetracker.domain.LedgerValidation
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** No Android or database dependencies: SAF owns streams and Room owns atomic replacement. */
class BackupCodec(private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    /** Does not close the caller's stream. The caller must close it before reporting success. */
    fun write(snapshot: LedgerSnapshot, output: OutputStream) {
        LedgerValidation.validateSnapshot(snapshot, nowMillis = clock())
        val database = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        if (database.size > ZipContainer.MAX_DATABASE_BYTES) {
            throw BackupException("数据量超过当前版本的备份上限（32 MB）。")
        }
        val counts = BackupRecordCounts.from(snapshot)
        val manifest = BackupManifest(
            format = FORMAT,
            backupVersion = BACKUP_VERSION,
            databaseVersion = DATABASE_VERSION,
            appVersion = APP_VERSION,
            exportTime = clock(),
            recordCount = counts.total,
            tableCounts = counts,
            sha256 = sha256(database),
        )
        ZipContainer.write(
            linkedMapOf(
                MANIFEST to json.encodeToString(manifest).toByteArray(Charsets.UTF_8),
                DATABASE to database,
            ), output,
        )
    }

    /** Reads and verifies everything before returning. This function never changes live data. */
    fun read(input: InputStream): PreparedBackup = try {
        val files = ZipContainer.read(input)
        val manifestText = decodeUtf8(files.getValue(MANIFEST))
        checkJsonStructure(manifestText)
        val manifestObject = json.parseToJsonElement(manifestText) as? JsonObject
            ?: throw BackupException(CORRUPT)
        // Check versions before decoding the version-specific manifest/schema.
        val backupVersion = (manifestObject["backupVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw BackupException("备份缺少有效的版本信息。")
        val databaseVersion = (manifestObject["databaseVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw BackupException("备份缺少有效的数据库版本信息。")
        if (backupVersion != BACKUP_VERSION || databaseVersion != DATABASE_VERSION) {
            throw BackupException("该备份来自过旧或不兼容版本，请使用支持此备份版本的 App。")
        }
        val manifest = json.decodeFromString<BackupManifest>(manifestText)
        require(manifest.format == FORMAT) { "这不是本 App 的完整备份文件。" }
        require(manifest.appVersion.isNotBlank() && manifest.appVersion.length <= 64) {
            "备份中的 App 版本信息无效。"
        }
        require(manifest.exportTime in 1..253402300799999L) { "备份导出时间无效。" }
        require(manifest.sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "备份缺少有效的 SHA-256 校验值。"
        }
        val database = files.getValue(DATABASE)
        require(sha256(database).equals(manifest.sha256, ignoreCase = true)) {
            "备份校验失败，数据文件可能已损坏或被修改。"
        }
        val databaseText = decodeUtf8(database)
        checkJsonStructure(databaseText)
        // No field defaults: missing collections/fields (including nullable fields) fail decoding.
        val snapshot = json.decodeFromString<LedgerSnapshot>(databaseText)
        val actualCounts = BackupRecordCounts.from(snapshot)
        require(manifest.tableCounts == actualCounts && manifest.recordCount == actualCounts.total) {
            "备份记录数量不一致，文件可能不完整。"
        }
        LedgerValidation.validateSnapshot(snapshot, nowMillis = clock())
        PreparedBackup(manifest, snapshot)
    } catch (error: CancellationException) {
        throw error
    } catch (error: BackupException) {
        throw error
    } catch (error: SerializationException) {
        throw BackupException("备份 JSON 损坏、缺少必要字段或格式不正确。", error)
    } catch (error: IllegalArgumentException) {
        throw BackupException(error.message ?: CORRUPT, error)
    } catch (error: java.io.IOException) {
        throw BackupException("无法读取完整备份，文件可能损坏或不可访问。", error)
    }

    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    /** Bound nesting before deserialization and reject ambiguous duplicate JSON object keys. */
    private fun checkJsonStructure(text: String) {
        data class Context(val isObject: Boolean, val keys: MutableSet<String>, var expectingKey: Boolean)
        val stack = ArrayDeque<Context>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{', '[' -> {
                    if (stack.size >= 12) throw BackupException("备份 JSON 嵌套过深，格式不正确。")
                    val isObject = text[index] == '{'
                    stack.addLast(Context(isObject, mutableSetOf(), isObject))
                }
                '}', ']' -> if (stack.isNotEmpty()) stack.removeLast()
                ',' -> stack.lastOrNull()?.let { if (it.isObject) it.expectingKey = true }
                '"' -> {
                    val start = index
                    index++
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\') index++
                        index++
                    }
                    if (index >= text.length) throw BackupException(CORRUPT)
                    val context = stack.lastOrNull()
                    if (context?.isObject == true && context.expectingKey) {
                        val key = json.decodeFromString<String>(text.substring(start, index + 1))
                        require(context.keys.add(key)) { "备份 JSON 含有重复字段。" }
                        context.expectingKey = false
                    }
                }
            }
            index++
        }
    }

    companion object {
        const val FORMAT = "cn.student.expensetracker.backup"
        const val BACKUP_VERSION = 1
        const val DATABASE_VERSION = 1
        const val APP_VERSION = "1.0.0"
        const val MANIFEST = "manifest.json"
        const val DATABASE = "database.json"
        internal const val CORRUPT = "备份文件损坏或格式不正确。"

        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
