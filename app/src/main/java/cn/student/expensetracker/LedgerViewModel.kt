package cn.student.expensetracker

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.student.expensetracker.backup.BackupCodec
import cn.student.expensetracker.backup.CsvExporter
import cn.student.expensetracker.backup.PreparedBackup
import cn.student.expensetracker.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val database = LedgerDatabase.build(application)
    private val repository = LedgerRepository(database)
    private val codec = BackupCodec()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _startupError = MutableStateFlow<String?>(null)
    val startupError = _startupError.asStateFlow()
    private val messages = Channel<String>(Channel.BUFFERED)
    val events: Flow<String> = messages.receiveAsFlow()
    private val _pendingBackup = MutableStateFlow<PreparedBackup?>(null)
    val pendingBackup = _pendingBackup.asStateFlow()

    private fun <T> Flow<List<T>>.observed(): StateFlow<List<T>> = catch { error ->
        if (error is CancellationException) throw error
        messages.send("读取本地数据失败，请关闭后重新打开。原有数据未被清空。")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val expenses = repository.observeExpenses().observed()
    val categories = repository.observeCategories().observed()
    val settings = repository.observeSettings().observed()

    init { initialize() }

    fun initialize() {
        if (_busy.value) return
        _busy.value = true
        _startupError.value = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.initialize() }
                _ready.value = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _startupError.value = "无法打开本地数据库。请检查可用存储空间后重试；请勿卸载或清除应用数据。"
            } finally { _busy.value = false }
        }
    }

    private fun perform(success: String? = null, action: suspend () -> Unit) {
        if (_busy.value || !_ready.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                action()
                success?.let { messages.send(it) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                messages.send(when (error) {
                    is IllegalArgumentException -> error.message ?: "输入内容无效，请检查后重试。"
                    is SecurityException -> "无法访问该文件，请重新选择文件或保存位置。"
                    is java.io.IOException -> error.message?.takeIf { it.any { ch -> ch.code > 127 } }
                        ?: "文件读写失败，请检查文件是否可用及存储空间。"
                    else -> "操作未完成，请检查存储空间后重试。原有记录不会被部分覆盖。"
                })
            } finally { _busy.value = false }
        }
    }

    fun saveExpense(expense: Expense, multiUse: MultiUseExpense?, onSuccess: (String) -> Unit) =
        perform("已保存消费") {
            withContext(Dispatchers.IO) { repository.saveExpense(expense, multiUse) }
            onSuccess(expense.id)
        }

    fun deleteExpense(id: String, onSuccess: () -> Unit) = perform("已删除消费及相关使用记录") {
        withContext(Dispatchers.IO) { repository.deleteExpense(id) }
        onSuccess()
    }

    fun addUsage(expenseId: String, usageDate: Long, note: String, onSuccess: () -> Unit) =
        perform("已记录一次使用") {
            withContext(Dispatchers.IO) { repository.addUsage(expenseId, usageDate, note) }
            onSuccess()
        }

    fun updateUsage(usage: UsageRecord, onSuccess: () -> Unit) = perform("已更新使用记录") {
        withContext(Dispatchers.IO) { repository.updateUsage(usage) }
        onSuccess()
    }

    fun deleteUsage(id: String, onSuccess: () -> Unit) = perform("已删除该次使用，成本已重新计算") {
        withContext(Dispatchers.IO) { repository.deleteUsage(id) }
        onSuccess()
    }

    fun setTheme(theme: String) = perform {
        withContext(Dispatchers.IO) { repository.setSetting("theme", theme) }
    }

    // A SAF result may arrive before Room opens after Android recreates the process.
    // Preserve the returned URI and wait instead of silently dropping that action.
    private fun afterStartup(action: () -> Unit) {
        viewModelScope.launch {
            ready.first { it }
            busy.first { !it }
            action()
        }
    }

    fun exportBackup(uri: Uri) = afterStartup { perform("完整备份已保存，可将文件传到新手机恢复") {
        withContext(Dispatchers.IO) {
            val snapshot = repository.snapshot()
            val resolver = getApplication<Application>().contentResolver
            val output = resolver.openOutputStream(uri, "wt")
                ?: throw java.io.IOException("无法创建备份文件，请换一个保存位置。")
            output.use { codec.write(snapshot, it) }
        }
    } }

    fun exportCsv(uri: Uri) = afterStartup { perform("CSV 已导出；迁移手机请使用完整备份") {
        withContext(Dispatchers.IO) {
            val snapshot = repository.snapshot()
            val resolver = getApplication<Application>().contentResolver
            val output = resolver.openOutputStream(uri, "wt")
                ?: throw java.io.IOException("无法创建 CSV 文件，请换一个保存位置。")
            output.use { CsvExporter.write(snapshot, it) }
        }
    } }

    fun prepareRestore(uri: Uri) = afterStartup { perform {
        _pendingBackup.value = null
        val prepared = withContext(Dispatchers.IO) {
            val input = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("无法读取所选文件，请重新选择。")
            input.use { codec.read(it) }
        }
        _pendingBackup.value = prepared
    } }

    fun cancelRestore() { if (!_busy.value) _pendingBackup.value = null }

    fun confirmRestore() {
        val prepared = _pendingBackup.value ?: return
        perform("完整恢复成功，消费、使用历史、分类和设置均已恢复") {
            withContext(Dispatchers.IO) { repository.restore(prepared.snapshot) }
            _pendingBackup.value = null
        }
    }
}
