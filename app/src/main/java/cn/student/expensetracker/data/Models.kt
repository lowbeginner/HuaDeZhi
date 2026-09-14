package cn.student.expensetracker.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import kotlinx.serialization.Serializable

object ExpenseType {
    const val SINGLE = "SINGLE"
    const val MULTI_USE = "MULTI_USE"
}

object MultiUseType {
    const val LIMITED = "LIMITED"
    const val UNLIMITED = "UNLIMITED"
}

@Serializable
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey val id: String,
    val name: String,
    val isDefault: Boolean,
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "expenses",
    foreignKeys = [ForeignKey(
        entity = Category::class,
        parentColumns = ["id"], childColumns = ["categoryId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("categoryId"), Index("purchaseDate"), Index("type")],
)
data class Expense(
    @PrimaryKey val id: String,
    val name: String,
    val amountCents: Long,
    val categoryId: String,
    /** Calendar date, stored as LocalDate.toEpochDay(); not milliseconds. */
    val purchaseDate: Long,
    val note: String,
    val type: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "multi_use_expenses",
    foreignKeys = [ForeignKey(
        entity = Expense::class,
        parentColumns = ["id"], childColumns = ["expenseId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class MultiUseExpense(
    @PrimaryKey val expenseId: String,
    val multiUseType: String,
    val startDate: Long,
    val endDate: Long?,
    val totalUses: Int?,
    val referenceSinglePriceCents: Long?,
)

@Serializable
@Entity(
    tableName = "usage_records",
    foreignKeys = [ForeignKey(
        entity = MultiUseExpense::class,
        parentColumns = ["expenseId"], childColumns = ["expenseId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("expenseId"), Index("usageDate")],
)
data class UsageRecord(
    @PrimaryKey val id: String,
    val expenseId: String,
    /** Instant in epoch milliseconds; display in the device's local time zone. */
    val usageDate: Long,
    val note: String,
    val createdAt: Long,
)

@Serializable
@Entity(tableName = "app_settings")
data class AppSetting(@PrimaryKey val key: String, val value: String)

data class ExpenseDetails(
    @Embedded val expense: Expense,
    @Relation(parentColumn = "id", entityColumn = "expenseId")
    val multiUse: MultiUseExpense?,
    @Relation(parentColumn = "id", entityColumn = "expenseId")
    val usages: List<UsageRecord>,
) {
    val usedCount: Int get() = usages.size
}

/** Entire persistent state. Every collection is required in backup JSON. */
@Serializable
data class LedgerSnapshot(
    val categories: List<Category>,
    val expenses: List<Expense>,
    val multiUse: List<MultiUseExpense>,
    val usageRecords: List<UsageRecord>,
    val settings: List<AppSetting>,
)
