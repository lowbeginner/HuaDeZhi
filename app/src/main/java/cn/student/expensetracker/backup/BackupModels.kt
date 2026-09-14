package cn.student.expensetracker.backup

import cn.student.expensetracker.data.LedgerSnapshot
import kotlinx.serialization.Serializable
import java.io.IOException

/** Versioned independently from Room so future releases can migrate older backups. */
@Serializable
data class BackupManifest(
    val format: String,
    val backupVersion: Int,
    val databaseVersion: Int,
    val appVersion: String,
    val exportTime: Long,
    /** Total number of persistent rows, including categories and settings. */
    val recordCount: Long,
    val tableCounts: BackupRecordCounts,
    /** SHA-256 of the exact UTF-8 database.json bytes; integrity, not encryption. */
    val sha256: String,
)

@Serializable
data class BackupRecordCounts(
    val categories: Int,
    val expenses: Int,
    val multiUse: Int,
    val usageRecords: Int,
    val settings: Int,
) {
    val total: Long
        get() = categories.toLong() + expenses + multiUse + usageRecords + settings

    companion object {
        fun from(snapshot: LedgerSnapshot) = BackupRecordCounts(
            snapshot.categories.size, snapshot.expenses.size, snapshot.multiUse.size,
            snapshot.usageRecords.size, snapshot.settings.size,
        )
    }
}

/** Successfully verified contents; applying them is a separate, confirmed Room transaction. */
data class PreparedBackup(val manifest: BackupManifest, val snapshot: LedgerSnapshot)

class BackupException(message: String, cause: Throwable? = null) : IOException(message, cause)
