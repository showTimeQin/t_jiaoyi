package com.tjiaoyi.ledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tjiaoyi.ledger.data.db.DatabaseHelper
import com.tjiaoyi.ledger.data.model.FX_NOTE_EXCHANGE
import com.tjiaoyi.ledger.data.model.FxRate
import com.tjiaoyi.ledger.data.model.FxRateRepository
import com.tjiaoyi.ledger.data.model.TradeSide
import com.tjiaoyi.ledger.data.model.formatDate
import com.tjiaoyi.ledger.data.model.fxText
import com.tjiaoyi.ledger.data.model.normalizeDateKey
import com.tjiaoyi.ledger.data.model.prettyDateKey

/**
 * 港股通「按日期结算汇率」的查看与维护。
 *
 * App 内置了上交所公布的 2014-11-17 起全量逐日结算汇兑比率（沪深同值），
 * 运行时离线查表。表最后一天之后的日期会回退到最近一个交易日，
 * 用户可以在这里按交割单补录 / 覆盖任意日期的汇率，自定义值优先。
 *
 * 内置表停在打包当天。录入做T记录时若买卖日期晚于本地最新可用交易日，
 * App 会在后台按该日期向上交所查一次，把之后新公布的交易日写进本地覆盖表，
 * 不需要手动点更新；断网或交易所尚未公布时回退到最近交易日，不影响录入。
 * 本页保留按交割单补录 / 覆盖任意日期的能力，并展示自动补入的数据。
 * 逐日汇率同时用于港币录入折算和港股通印花税按整港币取整。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FxRateScreen(
    databaseHelper: DatabaseHelper,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val customRates = remember(version) { databaseHelper.getAllFxRates() }
    val builtinCount = remember { FxRateRepository.count(context) }
    val firstDate = remember { prettyDateKey(FxRateRepository.firstDate(context)) }
    val lastDate = remember { prettyDateKey(FxRateRepository.lastDate(context)) }

    val effectiveLast = remember(version) {
        prettyDateKey(FxRateRepository.latestAvailableDate(context, databaseHelper))
    }

    var queryDate by remember { mutableStateOf(formatDate(System.currentTimeMillis())) }
    var editorRate by remember { mutableStateOf<FxRate?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf("") }

    val key = normalizeDateKey(queryDate)
    val buyLookup = if (key.length == 8) {
        FxRateRepository.lookupDate(context, databaseHelper, key, TradeSide.BUY)
    } else null
    val sellLookup = if (key.length == 8) {
        FxRateRepository.lookupDate(context, databaseHelper, key, TradeSide.SELL)
    } else null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("港股通结算汇率", style = MaterialTheme.typography.headlineMedium) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard {
                Text(
                    text = "内置历史表：$firstDate ~ $lastDate，共 $builtinCount 个交易日。",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "数据来自上海证券交易所逐日公布的港股通结算汇兑比率" +
                        "（深交所公布的数值相同），已随 App 打包，离线可查、不需要网络。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "交易所口径是反的：买入港股按当日「卖出结算汇兑比率」付出人民币，" +
                        "卖出港股按当日「买入结算汇兑比率」收到人民币。" +
                        "所以做T记录里买卖两侧各带一个汇率，跨日做T也准。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "本地目前最新可用到 $effectiveLast。添记录时若日期晚于它，" +
                        "App 会在后台按该日期向上交所补齐新公布的交易日并写入本地表，无需手动更新；" +
                        "断网或交易所还没公布时，先用不晚于它的最近一个交易日，不阻塞录入。" +
                        "如果和交割单对不上，可在下面按日期补录覆盖（你录入的值优先，不会被自动更新覆盖）。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "逐日汇率同时用于港币录入折算和港股通印花税按整港币取整。",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // ---------------------------------------------------------- 按日期试查
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = "查某一天的汇率",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = queryDate,
                        onValueChange = { queryDate = it },
                        label = { Text("日期（yyyy-MM-dd）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (key.length != 8) {
                        Text("日期格式应为 yyyy-MM-dd", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    } else if (buyLookup == null || sellLookup == null) {
                        Text("汇率表读取失败，将回退到费率设置里的默认值。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(
                            text = "买入港股用（卖出结算汇兑比率）：${fxText(buyLookup.rate)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "卖出港股用（买入结算汇兑比率）：${fxText(sellLookup.rate)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        val source = if (buyLookup.exact) {
                            "当天汇率"
                        } else {
                            "非交易日 / 表外日期，用 ${prettyDateKey(buyLookup.effectiveDate)} 的值"
                        }
                        val fromLabel = when {
                            buyLookup.note == FX_NOTE_EXCHANGE -> "（交易所自动补入）"
                            buyLookup.fromUser -> "（你自己录入的）"
                            else -> "（内置表）"
                        }
                        Text(
                            text = "$source$fromLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "例：港币 100 元成交，按买入侧汇率折合人民币 " +
                                "${String.format(java.util.Locale.US, "%.2f", 100 * buyLookup.rate)} 元",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            editorRate = customRates.firstOrNull { it.date == key }
                                ?: FxRate(date = key, buyRate = sellLookup?.rate ?: 0.0,
                                    sellRate = buyLookup?.rate ?: 0.0, fromUser = true)
                            showEditor = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = key.length == 8
                    ) { Text("把这一天录入 / 覆盖为自定义汇率") }
                }
            }

            // ---------------------------------------------------------- 自定义列表
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = "本地覆盖值（${customRates.size} 条：手工补录 + 自动补入）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (customRates.isEmpty()) {
                        Text(
                            text = "还没有补录值。内置表覆盖不到的日期、或者想按交割单用实际汇率，" +
                                "在上面按日期补一条即可；录入时自动补入的交易所数据也会列在这里。" +
                                "备份导出时会一起带上。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        customRates.forEach { rate ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(prettyDateKey(rate.date), style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold)
                                    Text(
                                        text = "买入 ${fxText(rate.sellRate)} / 卖出 ${fxText(rate.buyRate)}" +
                                            if (rate.note.isBlank()) "" else " · ${rate.note}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = {
                                    editorRate = rate
                                    showEditor = true
                                }) { Text("改") }
                                TextButton(onClick = { deleteTarget = rate.date }) { Text("删") }
                            }
                            androidx.compose.material3.Divider(color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    if (hint.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(hint, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    // -------------------------------------------------------------- 录入对话框
    if (showEditor && editorRate != null) {
        val initial = editorRate!!
        var dateText by remember(initial.date) { mutableStateOf(prettyDateKey(initial.date)) }
        var buyText by remember(initial.date) { mutableStateOf(if (initial.buyRate > 0) fxText(initial.buyRate) else "") }
        var sellText by remember(initial.date) { mutableStateOf(if (initial.sellRate > 0) fxText(initial.sellRate) else "") }
        var noteText by remember(initial.date) { mutableStateOf(initial.note) }
        var error by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text("自定义某日结算汇率") },
            text = {
                Column {
                    Text(
                        text = "买入结算汇兑比率 = 卖出港股收到人民币时用；" +
                            "卖出结算汇兑比率 = 买入港股付出人民币时用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        label = { Text("日期（yyyy-MM-dd）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = buyText,
                        onValueChange = { buyText = it },
                        label = { Text("买入结算汇兑比率") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sellText,
                        onValueChange = { sellText = it },
                        label = { Text("卖出结算汇兑比率") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("备注（可空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error.isNotBlank()) {
                        Text(error, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val normalized = normalizeDateKey(dateText)
                    val buy = buyText.toDoubleOrNull()
                    val sell = sellText.toDoubleOrNull()
                    error = when {
                        normalized.length != 8 -> "日期要写成 yyyy-MM-dd"
                        buy == null || buy <= 0.0 -> "买入结算汇兑比率要大于 0"
                        sell == null || sell <= 0.0 -> "卖出结算汇兑比率要大于 0"
                        else -> ""
                    }
                    if (error.isBlank()) {
                        val ok = databaseHelper.upsertFxRate(
                            FxRate(
                                date = normalized,
                                buyRate = buy!!,
                                sellRate = sell!!,
                                note = noteText.trim(),
                                fromUser = true
                            )
                        )
                        hint = if (ok) "已保存 ${prettyDateKey(normalized)} 的汇率" else "保存失败"
                        version++
                        showEditor = false
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }) { Text("取消") }
            }
        )
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除自定义汇率") },
            text = { Text("删除 ${prettyDateKey(deleteTarget!!)} 的自定义值后，这一天的汇率会回到内置表的值。") },
            confirmButton = {
                TextButton(onClick = {
                    databaseHelper.deleteFxRate(deleteTarget!!)
                    hint = "已删除 ${prettyDateKey(deleteTarget!!)} 的自定义汇率"
                    deleteTarget = null
                    version++
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            content()
        }
    }
}
