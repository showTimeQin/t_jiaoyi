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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.tjiaoyi.ledger.data.model.AppState
import com.tjiaoyi.ledger.data.model.FeeConfig
import com.tjiaoyi.ledger.data.model.FeeCalculator
import java.util.Locale

private fun trim(value: Double): String = FeeConfig.trimNumber(value)
private fun wanText(rate: Double): String = trim(rate * 10_000)
private fun pctText(rate: Double): String = trim(rate * 100)

private fun wanValue(text: String, fallback: Double): Double = FeeConfig.fromWan(text, fallback)
private fun pctValue(text: String, fallback: Double): Double {
    val v = text.trim().toDoubleOrNull() ?: return fallback
    return if (v < 0.0) fallback else v / 100.0
}
private fun plainValue(text: String, fallback: Double): Double {
    val v = text.trim().toDoubleOrNull() ?: return fallback
    return if (v < 0.0) fallback else v
}

/**
 * 手续费费率设置。
 *
 * 默认值按用户东方财富 A股交割单反推（全佣万2.355、最低5元买卖双向、沪市过户费万0.1、
 * 印花税万5仅卖出），港股通用腾讯 7 笔港股通交割单复核（万2.5佣金 + 万1.27规费 +
 * 双边 0.1% 印花税）。1.6.1 起印花税按整港币取整所用的汇率改为按每条记录买卖日期的
 * 真实结算汇兑比率，本页保留的 hkFx 只是查不到任何日期数据时的离线兜底。
 * 改完之后添加做T记录页会立即按新费率重算。
 *
 * 「不免5」= 佣金按 max(最低佣金, 成交额 × 费率) 收，买入、卖出各算一次，
 * 所以这里的最低佣金对买卖两边同时生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeeSettingsScreen(
    onBackClick: () -> Unit,
    onFxRatesClick: () -> Unit
) {
    val context = LocalContext.current
    val initial = AppState.feeConfig

    var aCommission by remember { mutableStateOf(wanText(initial.aCommissionRate)) }
    var aMin by remember { mutableStateOf(trim(initial.aMinCommission)) }
    var aTransfer by remember { mutableStateOf(wanText(initial.aTransferRate)) }
    var aStamp by remember { mutableStateOf(wanText(initial.aStampRate)) }

    var hkCommission by remember { mutableStateOf(wanText(initial.hkCommissionRate)) }
    var hkMin by remember { mutableStateOf(trim(initial.hkMinCommission)) }
    var hkStamp by remember { mutableStateOf(pctText(initial.hkStampRate)) }
    var hkLevy by remember { mutableStateOf(wanText(initial.hkLevyRate)) }
    var hkFrc by remember { mutableStateOf(wanText(initial.hkFrcRate)) }
    var hkTrading by remember { mutableStateOf(wanText(initial.hkTradingRate)) }
    var hkSettlement by remember { mutableStateOf(wanText(initial.hkSettlementRate)) }
    var hkFx by remember { mutableStateOf(trim(initial.hkFxRate)) }
    var hkRoundUp by remember { mutableStateOf(initial.hkStampRoundUpToHkd) }

    var aTarget by remember { mutableStateOf(pctText(initial.aTargetRate)) }
    var hkTarget by remember { mutableStateOf(pctText(initial.hkTargetRate)) }

    var showReset by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf(false) }

    fun buildConfig(): FeeConfig = FeeConfig(
        aCommissionRate = wanValue(aCommission, initial.aCommissionRate),
        aMinCommission = plainValue(aMin, initial.aMinCommission),
        aTransferRate = wanValue(aTransfer, initial.aTransferRate),
        aStampRate = wanValue(aStamp, initial.aStampRate),
        hkCommissionRate = wanValue(hkCommission, initial.hkCommissionRate),
        hkMinCommission = plainValue(hkMin, initial.hkMinCommission),
        hkStampRate = pctValue(hkStamp, initial.hkStampRate),
        hkLevyRate = wanValue(hkLevy, initial.hkLevyRate),
        hkFrcRate = wanValue(hkFrc, initial.hkFrcRate),
        hkTradingRate = wanValue(hkTrading, initial.hkTradingRate),
        hkSettlementRate = wanValue(hkSettlement, initial.hkSettlementRate),
        hkFxRate = plainValue(hkFx, initial.hkFxRate).coerceAtLeast(0.0001),
        hkStampRoundUpToHkd = hkRoundUp,
        aTargetRate = pctValue(aTarget, initial.aTargetRate),
        hkTargetRate = pctValue(hkTarget, initial.hkTargetRate)
    )

    // 用一笔 4 万元的买入 / 卖出实时演示当前费率，改一个数就能看到差多少
    val preview = buildConfig()
    val demoAmount = 40_000.0
    val demoABuy = FeeCalculator.buyFee(
        com.tjiaoyi.ledger.data.model.StockType.A_SHARE, demoAmount, preview
    )
    val demoASell = FeeCalculator.sellFee(
        com.tjiaoyi.ledger.data.model.StockType.A_SHARE, demoAmount, preview
    )
    val demoHkBuy = FeeCalculator.buyFee(
        com.tjiaoyi.ledger.data.model.StockType.HK_CONNECT, demoAmount, preview,
        fxRate = preview.hkFxRate
    )
    val demoHkSell = FeeCalculator.sellFee(
        com.tjiaoyi.ledger.data.model.StockType.HK_CONNECT, demoAmount, preview,
        fxRate = preview.hkFxRate
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手续费费率", style = MaterialTheme.typography.headlineMedium) },
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "费率按「成交额 × 费率」计算，佣金另有单笔最低额，买入、卖出各收一次" +
                        "（也就是你说的「不免5」）。\n" +
                        "港股通记录可以按港币填：按买卖日期的结算汇兑比率折成人民币入库，" +
                        "逐日汇率在「港股通结算汇率表」里查看和维护；日期超过内置表末日时，" +
                        "录入页会后台按该日期自动补齐交易所新公布的数据。\n" +
                        "印花税「按整港币向上取整」现在也用同一份逐日真实结算汇率：买入侧按买入日期、" +
                        "卖出侧按卖出日期，按人民币录入的记录同样适用。\n" +
                        "下面的汇率只是「内置表和自定义表都查不到」时的离线兜底，正常不会用到。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Section(title = "A股") {
                RateInput("佣金费率（万分之）", aCommission) { aCommission = it }
                RateInput("单笔最低佣金（元）", aMin) { aMin = it }
                RateInput("沪市过户费（万分之，双向）", aTransfer) { aTransfer = it }
                RateInput("印花税（万分之，仅卖出）", aStamp) { aStamp = it }
                Text(
                    text = "交割单口径：佣金 + 其他费用 = 全佣；沪市（6 开头）过户费单列，" +
                        "深市（0/3 开头）过户费为 0，已经含在全佣里，不重复收。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Section(title = "港股通") {
                RateInput("佣金费率（万分之）", hkCommission) { hkCommission = it }
                RateInput("单笔最低佣金（元人民币）", hkMin) { hkMin = it }
                RateInput("印花税（%，双边）", hkStamp) { hkStamp = it }
                RateInput("交易征费（万分之）", hkLevy) { hkLevy = it }
                RateInput("财汇局征费（万分之）", hkFrc) { hkFrc = it }
                RateInput("联交所交易费（万分之）", hkTrading) { hkTrading = it }
                RateInput("股份交收费（万分之）", hkSettlement) { hkSettlement = it }
                RateInput("离线兜底汇率（1 港币 = ? 元）", hkFx) { hkFx = it }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("印花税按整港币向上取整", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "不足 1 港币按 1 港币收",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = hkRoundUp, onCheckedChange = { hkRoundUp = it })
                }
                Text(
                    text = "1.6.1 起印花税取整不再用固定拟合值 0.9435，改为按该笔买卖日期的" +
                        "真实结算汇兑比率（默认值 0.85086 是内置表最后交易日的值）。" +
                        "这里填的数只在汇率表完全查不到时兜底；要改某一天的真实汇率，" +
                        "请到「港股通结算汇率表」按日期补录或在记录页手改。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "证券组合费按日终持仓市值逐日计提，券商多暂未向客户收取，这里不计入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onFxRatesClick,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("查看 / 维护按日期结算汇率表") }
            }

            Section(title = "目标到手收益率") {
                RateInput("A股（%）", aTarget) { aTarget = it }
                RateInput("港股通（%）", hkTarget) { hkTarget = it }
                Text(
                    text = "「到手收益率」= 扣掉买卖两边手续费后的净盈利 ÷ 买入成本。" +
                        "做T记录页用它反推建议卖出价。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Section(title = "试算：成交额 ¥40,000（A股按沪市口径）") {
                Text("A股　买入 ¥${money2(demoABuy)}　卖出 ¥${money2(demoASell)}")
                Text("港股通　买入 ¥${money2(demoHkBuy)}　卖出 ¥${money2(demoHkSell)}")
            }

            Button(
                onClick = {
                    AppState.saveFeeConfig(context, buildConfig())
                    savedHint = true
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存费率") }

            OutlinedButton(
                onClick = { showReset = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("恢复默认费率") }

            if (savedHint) {
                Text(
                    text = "已保存。新添加 / 编辑的做T记录会按这个费率自动算手续费。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("恢复默认费率") },
            text = { Text("会把全部费率改回按你交割单反推的默认值，已经保存过的做T记录不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    AppState.resetFeeConfig(context)
                    showReset = false
                    onBackClick()
                }) { Text("恢复默认") }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) { Text("取消") }
            }
        )
    }
}

private fun money2(value: Double): String = String.format(Locale.US, "%,.2f", value)

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun RateInput(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
