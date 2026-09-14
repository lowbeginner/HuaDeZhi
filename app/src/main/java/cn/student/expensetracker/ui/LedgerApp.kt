package cn.student.expensetracker.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import cn.student.expensetracker.LedgerViewModel
import cn.student.expensetracker.data.ExpenseType
import kotlinx.coroutines.delay
import java.time.LocalDate

private data class MainDestination(val route: String, val label: String, val icon: ImageVector)
private val destinations = listOf(
    MainDestination("home", "首页", Icons.Default.Home),
    MainDestination("records", "记录", Icons.AutoMirrored.Filled.ReceiptLong),
    MainDestination("stats", "统计", Icons.Default.BarChart),
    MainDestination("settings", "设置", Icons.Default.Settings),
)

@Composable
fun LedgerApp(vm: LedgerViewModel, onExportBackup: () -> Unit, onImportBackup: () -> Unit, onExportCsv: () -> Unit) {
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    val isMain = destinations.any { it.route == route }
    var addChooser by rememberSaveable { mutableStateOf(false) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(it, withDismissAction = true) } }
    LaunchedEffect(Unit) { while (true) { today = LocalDate.now(); delay(30_000) } }
    fun openMain(target: String) {
        nav.navigate(target) { popUpTo(nav.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true }
    }
    Scaffold(
        topBar = { if (isMain) PageBar(if (route == "home") "花得值" else destinations.first { it.route == route }.label) },
        bottomBar = {
            if (isMain) NavigationBar {
                destinations.forEach { destination -> NavigationBarItem(selected = route == destination.route, onClick = { openMain(destination.route) }, enabled = ready && !busy, icon = { Icon(destination.icon, null) }, label = { Text(destination.label) }, modifier = Modifier.testTag("nav_${destination.route}")) }
            }
        },
        floatingActionButton = {
            if (ready && !busy && (route == "home" || route == "records")) ExtendedFloatingActionButton(onClick = { addChooser = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("记一笔") }, modifier = Modifier.testTag("add_expense"))
        },
        snackbarHost = { SnackbarHost(snackbar, modifier = if (isMain) Modifier else Modifier.navigationBarsPadding().imePadding()) },
        // Detail/form screens own their system insets; main tabs use this scaffold's insets.
        contentWindowInsets = if (isMain) ScaffoldDefaults.contentWindowInsets else WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (!ready) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { CircularProgressIndicator(); Text("正在打开本地账本…") }
            } else {
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") { HomeScreen(expenses, categories, today, onOpen = { nav.navigate("detail/${Uri.encode(it)}") }, onRecords = { openMain("records") }) }
                    composable("records") { RecordsScreen(expenses, categories, today) { nav.navigate("detail/${Uri.encode(it)}") } }
                    composable("stats") { StatsScreen(expenses, categories, today) }
                    composable("settings") { SettingsScreen(settings, categories, busy, vm::setTheme, onExportBackup, onImportBackup, onExportCsv) }
                    composable("add/{type}", arguments = listOf(navArgument("type") { type = NavType.StringType })) { backStack ->
                        val type = backStack.arguments?.getString("type") ?: ExpenseType.SINGLE
                        ExpenseForm(null, type, categories, busy, onBack = { nav.popBackStack() }) { expense, multi ->
                            vm.saveExpense(expense, multi) { id -> nav.popBackStack(); nav.navigate("detail/${Uri.encode(id)}") }
                        }
                    }
                    composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { backStack ->
                        val item = expenses.firstOrNull { it.expense.id == backStack.arguments?.getString("id") }
                        if (item != null) ExpenseForm(item, item.expense.type, categories, busy, onBack = { nav.popBackStack() }) { expense, multi ->
                            vm.saveExpense(expense, multi) { nav.popBackStack() }
                        } else MissingRecord { nav.popBackStack() }
                    }
                    composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { backStack ->
                        val id = backStack.arguments?.getString("id").orEmpty()
                        val item = expenses.firstOrNull { it.expense.id == id }
                        if (item != null) DetailScreen(item, categories.firstOrNull { it.id == item.expense.categoryId }?.name ?: "其他", today, busy,
                            onBack = { nav.popBackStack() }, onEdit = { nav.navigate("edit/${Uri.encode(id)}") }, onDeleteExpense = { vm.deleteExpense(id) { nav.popBackStack() } },
                            onAddUsage = { time, note, done -> vm.addUsage(id, time, note, done) }, onUpdateUsage = vm::updateUsage, onDeleteUsage = vm::deleteUsage)
                        else MissingRecord { nav.popBackStack() }
                    }
                }
            }
            if (busy && ready && isMain) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    if (addChooser) AlertDialog(onDismissRequest = { addChooser = false }, title = { Text("记一笔") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("这笔消费属于哪一种？", style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = { addChooser = false; nav.navigate("add/${ExpenseType.SINGLE}") }, modifier = Modifier.fillMaxWidth().testTag("add_single")) { Text("新增单次消费") }
            FilledTonalButton(onClick = { addChooser = false; nav.navigate("add/${ExpenseType.MULTI_USE}") }, modifier = Modifier.fillMaxWidth().testTag("add_multi")) { Text("新增多次消费") }
        }
    }, confirmButton = { TextButton(onClick = { addChooser = false }) { Text("取消") } })
}

@Composable
private fun MissingRecord(onBack: () -> Unit) {
    Scaffold(topBar = { PageBar("消费详情", onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { EmptyState("记录暂不可用", "记录可能已删除，或正在更新。请返回账本查看。") }
    }
}
