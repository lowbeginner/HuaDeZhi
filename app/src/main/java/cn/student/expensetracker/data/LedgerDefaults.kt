package cn.student.expensetracker.data

object LedgerDefaults {
    val settings: List<AppSetting> = listOf(
        AppSetting("theme", "SYSTEM"),
        AppSetting("currency", "CNY"),
    )

    fun categories(createdAt: Long = System.currentTimeMillis()): List<Category> = listOf(
        "food" to "餐饮", "transport" to "交通", "shopping" to "购物",
        "entertainment" to "娱乐", "fitness" to "健身", "study" to "学习",
        "daily" to "生活", "medical" to "医疗", "digital" to "数码",
        "subscription" to "会员订阅", "travel" to "旅行", "other" to "其他",
    ).mapIndexed { index, (id, name) -> Category(id, name, true, createdAt + index) }
}
