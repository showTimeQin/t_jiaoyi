package com.tjiaoyi.ledger.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tjiaoyi.ledger.data.backup.ImportMode
import com.tjiaoyi.ledger.data.backup.JsonlBackup
import com.tjiaoyi.ledger.data.db.DatabaseHelper
import com.tjiaoyi.ledger.data.model.AppState
import com.tjiaoyi.ledger.data.model.FeeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据导入导出页。
 *
 * 文件读写全部走系统的文件选择器（SAF），App 不需要任何存储权限。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataTransferScreen(
    databaseHelper: DatabaseHelper,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var counts by remember { mutableStateOf(JsonlBackup.countOf(databaseHelper)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var pendingImportMode by remember { mutableStateOf<ImportMode?>(null) }
    var showFormatSample by remember { mutableStateOf(false) }

    fun refreshCounts() {
        counts = JsonlBackup.countOf(databaseHelper)
    }

    // 导出：先让用户挑保存位置，再往拿到的 Uri 里写
    //
    // MIME 类型用 octet-stream 而不是 application/json：系统文件选择器会按 MIME 补后缀，
    // 用 application/json 会把 xxx.jsonl 存成 .jsonl.json。
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri == null) {
            messageIsError = false
            message = "已取消导出。"
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            busy = true
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("无法写入所选位置")
                    stream.bufferedWriter().use { writer ->
                        JsonlBackup.export(databaseHelper, writer, AppState.feeConfig)
                    }
                }
            }
            busy = false

            outcome.fold(
                onSuccess = { result ->
                    messageIsError = false
                    message = "导出成功：${result.describe()}"
                },
                onFailure = { e ->
                    messageIsError = true
                    message = "导出失败：${e.message ?: e::class.java.simpleName}"
                }
            )
        }
    }

    // 导入：先让用户挑文件，再按之前选定的方式写入
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val mode = pendingImportMode ?: ImportMode.APPEND
        pendingImportMode = null

        if (uri == null) {
            messageIsError = false
            message = "已取消导入。"
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            busy = true
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: error("无法读取所选文件")
                    stream.bufferedReader().use { reader ->
                        JsonlBackup.import(databaseHelper, reader, mode)
                    }
                }
            }
            busy = false
            refreshCounts()

            outcome.fold(
                onSuccess = { result ->
                    messageIsError = false
                    // 新备份自带费率设置；老备份没有这一行时保持本机设置不变。
                    // 1.6.1 之前备份里存的是为凑交割单反推的固定拟合汇率，如今它只是
                    // 离线兜底，导入时换成内置表最后交易日的真实值，避免旧值又被写回设置页。
                    result.feeConfig?.let { imported ->
                        val legacyFx = listOf(0.8527, 0.9435)
                        val cleaned = if (legacyFx.any {
                                kotlin.math.abs(imported.hkFxRate - it) < 0.0001
                            }
                        ) imported.copy(hkFxRate = FeeConfig().hkFxRate) else imported
                        AppState.saveFeeConfig(context, cleaned)
                    }
                    AppState.notifyAccountsChanged()
                    message = buildString {
                        append(if (mode == ImportMode.REPLACE) "覆盖导入成功：" else "追加导入成功：")
                        append(result.counts.describe())
                        if (result.feeConfig != null) append("\n已一并恢复备份里的手续费费率。")
                        if (result.skipped > 0) append("\n跳过 ${result.skipped} 条无法识别的记录。")
                        for (warning in result.warnings) {
                            append("\n· ")
                            append(warning)
                        }
                    }
                },
                onFailure = { e ->
                    messageIsError = true
                    message = "导入失败：${e.message ?: e::class.java.simpleName}\n数据库没有被改动。"
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据导入导出", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------------------------------------------------- 结果（放最上面，操作完一眼能看到）
            message?.let { text ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (messageIsError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (messageIsError) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { message = null }) { Text("知道了") }
                    }
                }
            }

            // ---------------------------------------------------- 当前数据
            SectionCard(title = "当前数据") {
                Text(text = counts.describe(), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { refreshCounts() }, enabled = !busy) { Text("刷新") }
                    if (busy) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "处理中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---------------------------------------------------- 导出
            SectionCard(title = "导出") {
                Text(
                    text = "把全部分账户、股票、做T记录和手续费费率写成一个 JSONL 文件" +
                        "（每行一条记录），保存位置由你自己选。建议在覆盖导入前先导出备份。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { exportLauncher.launch(defaultFileName()) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                ) {
                    Text("导出为 JSONL 文件")
                }
            }

            // ---------------------------------------------------- 导入
            SectionCard(title = "导入") {
                Text(
                    text = "覆盖导入：先清空现有数据，再按文件完全还原（保留原始编号）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { pendingImportMode = ImportMode.REPLACE },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("覆盖导入")
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "追加导入：保留现有数据，把文件里的记录并进来（重新分配编号）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { pendingImportMode = ImportMode.APPEND },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                ) {
                    Text("追加导入")
                }
            }

            // ---------------------------------------------------- 格式说明
            SectionCard(title = "文件格式") {
                Text(
                    text = "JSONL（JSON Lines）：整个文件是一行一条的 JSON，不在行尾加逗号。" +
                        "用 _type 区分记录属于哪张表，可以流式读取，单行损坏不影响其它行。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { showFormatSample = !showFormatSample }) {
                    Text(if (showFormatSample) "收起示例" else "查看示例")
                }

                if (showFormatSample) {
                    for (line in FORMAT_SAMPLE) {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // 导入前的二次确认
    pendingImportMode?.let { mode ->
        val isReplace = mode == ImportMode.REPLACE
        AlertDialog(
            onDismissRequest = { pendingImportMode = null },
            title = { Text(if (isReplace) "覆盖导入" else "追加导入") },
            text = {
                Text(
                    text = if (isReplace) {
                        "将先清空当前全部数据（${counts.describe()}），再按文件内容恢复，编号保持不变。\n\n" +
                            "此操作不可撤销，建议先导出一份备份。"
                    } else {
                        "将保留当前全部数据（${counts.describe()}），把文件里的记录并入进来，" +
                            "新增的记录会重新分配编号，不会覆盖现有数据。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Text(
                        text = if (isReplace) "选择文件并覆盖" else "选择文件并追加",
                        color = if (isReplace) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportMode = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

private fun defaultFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    return "t_ledger_backup_$stamp.jsonl"
}

private val FORMAT_SAMPLE = listOf(
    """{"_type":"meta","app":"tj-ledger","schema":2,"counts":{...}}""",
    """{"_type":"account","id":1,"name":"默认账户",...}""",
    """{"_type":"stock","id":1,"name":"腾讯控股","code":"00700","account_id":1,"stock_type":"HK",...}""",
    """{"_type":"trade","id":1,"stock_id":1,"buy_price":380.0,"buy_amount":38000.0,...}""",
    """{"_type":"fee_config","a_commission_rate":0.000225,...}"""
)
