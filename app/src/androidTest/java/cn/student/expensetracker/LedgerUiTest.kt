package cn.student.expensetracker

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.student.expensetracker.data.LedgerDatabase
import cn.student.expensetracker.data.LedgerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises real navigation, validated forms and Room persistence through the app UI. */
@RunWith(AndroidJUnit4::class)
class LedgerUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val marker = "UI测试-${UUID.randomUUID().toString().take(8)}"

    @After fun removeOnlyTestFixtures() = runBlocking {
        val repository = LedgerRepository(LedgerDatabase.build(compose.activity))
        repository.observeExpenses().first().filter { it.expense.name.startsWith(marker) }
            .forEach { repository.deleteExpense(it.expense.id) }
    }

    @Test fun createEditDeleteAndLimitedUsage() {
        waitForTag("add_expense")
        compose.onNodeWithTag("add_expense").performClick()
        compose.onNodeWithTag("add_single").performClick()
        compose.onNodeWithTag("expense_name").performTextInput("$marker 晚饭")
        compose.onNodeWithTag("expense_amount").performTextInput("28")
        compose.onNodeWithTag("save_expense").performScrollTo().performClick()
        waitForTag("detail_cost")
        compose.onNodeWithTag("detail_cost").assertTextEquals("¥28.00")

        compose.onNodeWithTag("edit_expense").performClick()
        compose.onNodeWithTag("expense_amount").performTextReplacement("32.50")
        compose.onNodeWithTag("save_expense").performScrollTo().performClick()
        waitForTag("detail_cost")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("detail_cost") and hasText("¥32.50")).fetchSemanticsNodes().isNotEmpty() }
        deleteFromDetail()

        waitForTag("add_expense")
        compose.onNodeWithTag("add_expense").performClick()
        compose.onNodeWithTag("add_multi").performClick()
        compose.onNodeWithTag("expense_name").performTextInput("$marker 游泳卡")
        compose.onNodeWithTag("expense_amount").performTextInput("60")
        closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithTag("multi_limited").performScrollTo().performClick()
        waitForTag("expense_total_uses")
        compose.onNodeWithTag("expense_total_uses").performScrollTo().performTextInput("1")
        compose.onNodeWithTag("save_expense").performScrollTo().performClick()
        waitForTag("detail_used_count")
        compose.onNodeWithTag("detail_used_count").assertTextEquals("已使用 0 / 1 次")
        compose.onNodeWithTag("detail_cost").assertTextEquals("尚未使用")
        compose.onNodeWithTag("use_once").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("detail_used_count") and hasText("已使用 1 / 1 次")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail_cost").assertTextEquals("¥60.00 / 次")
        compose.onNodeWithTag("use_once").assertIsNotEnabled()
        deleteFromDetail()
    }

    private fun deleteFromDetail() {
        compose.onNodeWithContentDescription("删除消费").performClick()
        compose.onNodeWithTag("confirm_delete_expense").performClick()
        waitForTag("add_expense")
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
}
