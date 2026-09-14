package cn.student.expensetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.student.expensetracker.ui.ExpenseTheme
import cn.student.expensetracker.ui.LedgerApp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val vm: LedgerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val startupError by vm.startupError.collectAsStateWithLifecycle()
            val pending by vm.pendingBackup.collectAsStateWithLifecycle()
            val busy by vm.busy.collectAsStateWithLifecycle()
            val backupLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/octet-stream")
            ) { uri -> uri?.let(vm::exportBackup) }
            val csvLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("text/csv")
            ) { uri -> uri?.let(vm::exportCsv) }
            val restoreLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let(vm::prepareRestore) }
            ExpenseTheme(settings.firstOrNull { it.key == "theme" }?.value ?: "SYSTEM") {
                if (startupError != null) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.safeDrawingPadding().padding(28.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("账本暂时无法打开", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(16.dp))
                            Text(startupError.orEmpty())
                            Spacer(Modifier.height(20.dp))
                            Button(onClick = vm::initialize, enabled = !busy) { Text("重试") }
                        }
                    }
                } else {
                    LedgerApp(vm,
                        onExportBackup = { backupLauncher.launch("ExpenseBackup_${fileTime()}.expensebackup") },
                        onImportBackup = { restoreLauncher.launch(arrayOf("*/*")) },
                        onExportCsv = { csvLauncher.launch("Expenses_${fileTime()}.csv") })
                }
                pending?.let { backup ->
                    AlertDialog(
                        onDismissRequest = { if (!busy) vm.cancelRestore() },
                        title = { Text("覆盖恢复完整备份？") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("已验证文件完整性：${backup.snapshot.expenses.size} 笔消费、${backup.snapshot.usageRecords.size} 条使用记录、${backup.snapshot.categories.size} 个分类。")
                                Text("恢复备份将删除当前设备上的已有记录，并替换为备份中的数据，是否继续？建议先导出当前账本。")
                                Text("第一版支持完全覆盖恢复。", style = MaterialTheme.typography.bodySmall)
                                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        },
                        confirmButton = { TextButton(onClick = vm::confirmRestore, enabled = !busy) { Text("确认覆盖恢复") } },
                        dismissButton = { TextButton(onClick = vm::cancelRestore, enabled = !busy) { Text("取消") } },
                    )
                }
            }
        }
    }

    private fun fileTime(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
}
