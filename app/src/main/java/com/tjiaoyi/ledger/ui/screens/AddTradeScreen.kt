package com.tjiaoyi.ledger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Divider
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.tjiaoyi.ledger.data.model.AppState
import com.tjiaoyi.ledger.data.model.Currency
import com.tjiaoyi.ledger.data.model.FX_NOTE_EXCHANGE
import com.tjiaoyi.ledger.data.model.FeeCalculator
import com.tjiaoyi.ledger.data.model.FxLookup
import com.tjiaoyi.ledger.data.model.FxRateRepository
import com.tjiaoyi.ledger.data.model.FxSyncResult
import com.tjiaoyi.ledger.data.model.StockType
import com.tjiaoyi.ledger.data.model.Trade
import com.tjiaoyi.ledger.data.model.TradeSide
import com.tjiaoyi.ledger.data.model.dateKey
import com.tjiaoyi.ledger.data.model.formatDate
import com.tjiaoyi.ledger.data.model.fxText
import com.tjiaoyi.ledger.data.model.nowStamp
import com.tjiaoyi.ledger.data.model.parseDateOrNull
import com.tjiaoyi.ledger.data.model.prettyDateKey
import com.tjiaoyi.ledger.data.model.startOfDay
import com.tjiaoyi.ledger.ui.components.TradeCard
import com.tjiaoyi.ledger.ui.components.priceText
import com.tjiaoyi.ledger.ui.components.signedMoney
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 添加 / 编辑一条做T记录。
 *
 * 买入侧必填；卖出侧由「已卖出」开关控制，关掉就是「未出T」。
 * 买卖时间都只选到「天」，不记时刻。
 *
 * 手续费按股票类型（A股 / 港股通）自动算，算完仍可直接手改；手改过之后不再自动覆盖，
 * 想恢复自动就点「按费率重算」。「不免5」体现在 FeeCalculator 里：买卖各收一次最低佣金。
 *
 * 港股通可以选择按「人民币」或「港币」录入价格与金额：
 * - 按港币填时，买入 / 卖出各按自己日期的**结算汇兑比率**折成人民币再入库；
 *   手续费始终按人民币成交额算，卡片、统计、盈利也一律显示人民币。
 * - 汇率来自内置的逐日历史表（上交所/深交所公布），日期改了会自动带出；
 *   日期晚于本地表末日时，后台按该日期向上交所补齐新公布的交易日并写入本地表，
 *   断网或交易所尚未公布就回退到最近一个交易日，不阻塞录入，也可手改成实际值。
 * - 原始港币数值和所用汇率一起存进记录，回看时以小字显示，方便和交割单对账。
 * - 这个真实汇率同时用于港股通印花税「按整港币取整」：按人民币录入的记录也按
 *   买卖日期自动带出（只是不显示输入框），不再用费率设置里的固定拟合值。
 *
 * 交易所口径（名字是反的）：买入交易日按「卖出结算汇兑比率」付出人民币，
 * 卖出交易日按「买入结算汇兑比率」收到人民币，所以两侧各带一个汇率。
 *
 * 买入价下面的「建议卖出价」是反解出来的：让扣完买卖双边手续费后的净盈利
 * 恰好等于买入成本的 4%（A股）或 3%（港股通）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTradeScreen(
    stockId: Long,
    databaseHelper: DatabaseHelper,
    onBackClick: () -> Unit,
    tradeId: Long = 0L
) {
    val context = LocalContext.current
    val existing = remember(tradeId) {
        if (tradeId > 0L) databaseHelper.getTradeById(tradeId) else null
    }
    val editing = existing != null
    val stock = remember(stockId) { databaseHelper.getStockById(stockId) }
    val stockName = stock?.name ?: "做T记录"
    val stockCode = stock?.code ?: ""
    val stockType = stock?.stockType ?: StockType.A_SHARE
    val feeConfig = AppState.feeConfig
    val supportsHkd = stockType == StockType.HK_CONNECT

    val nowMillis = System.currentTimeMillis()

    // 老记录 / A股一律按人民币打开；港币录入的记录按原币种打开，值用当初填的原币值还原
    val editHkd = supportsHkd && existing?.isHkdInput == true
    var currency by remember {
        mutableStateOf(if (editHkd) Currency.HKD else Currency.CNY)
    }
    val isHkd = supportsHkd && currency == Currency.HKD

    var buyDate by remember {
        mutableStateOf(startOfDay(parseDateOrNull(existing?.buyTime) ?: nowMillis))
    }
    var sellDate by remember {
        mutableStateOf(startOfDay(parseDateOrNull(existing?.sellTime) ?: nowMillis))
    }

    fun initialText(cny: Double?, original: Double?): String {
        val value = if (editHkd) (original ?: cny) else cny
        return if (value != null && value > 0.0) numberText(value) else ""
    }

    var buyPrice by remember {
        mutableStateOf(initialText(existing?.buyPrice, existing?.buyPriceOriginal))
    }
    var buyAmount by remember {
        mutableStateOf(initialText(existing?.buyAmount, existing?.buyAmountOriginal))
    }
    var buyFee by remember {
        mutableStateOf(if (existing != null && existing.buyFee > 0.0) numberText(existing.buyFee) else "")
    }

    var hasSell by remember { mutableStateOf(existing?.isClosed ?: false) }
    var sellPrice by remember {
        mutableStateOf(initialText(existing?.sellPrice, existing?.sellPriceOriginal))
    }
    var sellAmount by remember {
        mutableStateOf(initialText(existing?.sellAmount, existing?.sellAmountOriginal))
    }
    var sellFee by remember {
        mutableStateOf(
            if (existing != null && (existing.sellFee ?: 0.0) > 0.0) {
                numberText(existing.sellFee ?: 0.0)
            } else ""
        )
    }

    // 已经手工填过费率的记录保留原值；没填过的（0 / 空）才接管自动计算
    var autoBuyFee by remember {
        mutableStateOf(existing == null || existing.buyFee <= 0.0)
    }
    var autoSellFee by remember {
        mutableStateOf(existing == null || (existing.sellFee ?: 0.0) <= 0.0)
    }

    // ---------------------------------------------------------------- 按日期汇率
    val savedBuyFx = if (editHkd) (existing?.buyFxRate ?: 0.0) else 0.0
    val savedSellFx = if (editHkd) (existing?.sellFxRate ?: 0.0) else 0.0

    var buyFxText by remember {
        mutableStateOf(
            fxText(
                if (savedBuyFx > 0.0) savedBuyFx
                else FxRateRepository.lookup(context, databaseHelper, buyDate, TradeSide.BUY)?.rate
                    ?: feeConfig.hkFxRate
            )
        )
    }
    var sellFxText by remember {
        mutableStateOf(
            fxText(
                if (savedSellFx > 0.0) savedSellFx
                else FxRateRepository.lookup(context, databaseHelper, sellDate, TradeSide.SELL)?.rate
                    ?: feeConfig.hkFxRate
            )
        )
    }
    // 编辑港币记录时沿用记录里存的汇率，不被日期自动覆盖；点「按日期重取」才重新查表
    var buyFxManual by remember { mutableStateOf(savedBuyFx > 0.0) }
    var sellFxManual by remember { mutableStateOf(savedSellFx > 0.0) }
    var buyFxRefresh by remember { mutableStateOf(0) }
    var sellFxRefresh by remember { mutableStateOf(0) }
    var buyFxInfo by remember {
        mutableStateOf(if (savedBuyFx > 0.0) "沿用这条记录保存的汇率" else "")
    }
    var sellFxInfo by remember {
        mutableStateOf(if (savedSellFx > 0.0) "沿用这条记录保存的汇率" else "")
    }

    // 港股通两种录入方式都要按日期取真实汇率：港币录入用于折算，人民币录入用于
    // 印花税取整。日期超出本地表末日时后台按该日期补一次交易所数据再重新查表。
    LaunchedEffect(buyDate, isHkd, buyFxManual, buyFxRefresh) {
        if (buyFxManual || !supportsHkd) return@LaunchedEffect
        val key = dateKey(buyDate)
        val needSync = key > FxRateRepository.latestAvailableDate(context, databaseHelper)
        if (needSync) buyFxInfo = "正在按买入日期查询交易所结算汇率…"
        val sync = if (needSync) {
            withContext(Dispatchers.IO) {
                FxRateRepository.syncForDate(context, databaseHelper, key, force = buyFxRefresh > 0)
            }
        } else null
        val found = FxRateRepository.lookupDate(context, databaseHelper, key, TradeSide.BUY)
        if (found != null) {
            buyFxText = fxText(found.rate)
            buyFxInfo = describeFxLookup(found, sync)
        } else if (needSync) {
            buyFxInfo = "暂时查不到汇率，可按交割单手填或到汇率表页补录"
        }
    }

    LaunchedEffect(sellDate, isHkd, sellFxManual, sellFxRefresh) {
        if (sellFxManual || !supportsHkd) return@LaunchedEffect
        val key = dateKey(sellDate)
        val needSync = key > FxRateRepository.latestAvailableDate(context, databaseHelper)
        if (needSync) sellFxInfo = "正在按卖出日期查询交易所结算汇率…"
        val sync = if (needSync) {
            withContext(Dispatchers.IO) {
                FxRateRepository.syncForDate(context, databaseHelper, key, force = sellFxRefresh > 0)
            }
        } else null
        val found = FxRateRepository.lookupDate(context, databaseHelper, key, TradeSide.SELL)
        if (found != null) {
            sellFxText = fxText(found.rate)
            sellFxInfo = describeFxLookup(found, sync)
        } else if (needSync) {
            sellFxInfo = "暂时查不到汇率，可按交割单手填或到汇率表页补录"
        }
    }

    val buyFx = buyFxText.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 0.0
    val sellFx = sellFxText.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 0.0

    // 港股通印花税按整港币取整要用「这一侧日期」的真实结算汇兑比率；
    // 按港币录入时它就是上面的折算汇率，按人民币录入时也由日期自动带出。
    val buyFeeFx = if (stockType == StockType.HK_CONNECT) buyFx else 0.0
    val sellFeeFx = if (stockType == StockType.HK_CONNECT) sellFx else 0.0

    /** 输入框按当前币种解释，统一折成人民币后再参与计算 / 落库。 */
    fun toCny(text: String, fx: Double): Double? {
        val value = text.toDoubleOrNull() ?: return null
        return if (isHkd) value * (if (fx > 0.0) fx else 1.0) else value
    }

    val buyPriceCny = toCny(buyPrice, buyFx)
    val buyAmountCny = toCny(buyAmount, buyFx)
    val sellPriceCny = if (hasSell) toCny(sellPrice, sellFx) else 0.0
    val sellAmountCny = if (hasSell) toCny(sellAmount, sellFx) else 0.0

    // 金额或费率一变就重算；用户手改过手续费后 autoXxxFee 变 false，自动计算就让位
    LaunchedEffect(buyAmountCny, stockType, feeConfig, autoBuyFee, buyFeeFx) {
        if (!autoBuyFee) return@LaunchedEffect
        buyFee = if (buyAmountCny != null && buyAmountCny > 0.0) {
            numberText(FeeCalculator.buyFee(stockType, buyAmountCny, feeConfig, stockCode, buyFeeFx))
        } else {
            ""
        }
    }

    LaunchedEffect(sellAmountCny, hasSell, stockType, feeConfig, autoSellFee, sellFeeFx) {
        if (!autoSellFee) return@LaunchedEffect
        sellFee = if (hasSell && sellAmountCny != null && sellAmountCny > 0.0) {
            numberText(FeeCalculator.sellFee(stockType, sellAmountCny, feeConfig, stockCode, sellFeeFx))
        } else {
            ""
        }
    }

    // 正在选择日期的是哪一侧：0 买入、1 卖出
    var datePickerFor by remember { mutableStateOf<Int?>(null) }

    val buyTimeText = formatDate(buyDate)
    val sellTimeText = formatDate(sellDate)

    val buyFeeValue = buyFee.toDoubleOrNull() ?: 0.0
    val sellFeeValue = if (hasSell) (sellFee.toDoubleOrNull() ?: 0.0) else 0.0

    // 反解建议卖出价 + 按建议价到手的盈利（一律用人民币口径）
    val suggestion = remember(
        buyPriceCny, buyAmountCny, buyFeeValue, stockType, stockCode, feeConfig, sellFeeFx
    ) {
        FeeCalculator.suggest(
            type = stockType,
            buyPrice = buyPriceCny ?: 0.0,
            buyAmount = buyAmountCny ?: 0.0,
            buyFee = buyFeeValue,
            config = feeConfig,
            stockCode = stockCode,
            sellFxRate = sellFeeFx
        )
    }

    val sellBeforeBuy = hasSell &&
        (parseDateOrNull(sellTimeText) ?: 0L) < (parseDateOrNull(buyTimeText) ?: 0L)

    val problem = when {
        isHkd && buyFx <= 0.0 -> "请填写有效的买入折算汇率"
        isHkd && hasSell && sellFx <= 0.0 -> "请填写有效的卖出折算汇率"
        buyPriceCny == null || buyPriceCny <= 0.0 -> "请填写买入价"
        buyAmountCny == null || buyAmountCny <= 0.0 -> "请填写买入金额"
        buyFeeValue < 0.0 -> "买入手续费不能为负"
        !hasSell -> null
        sellPriceCny == null || sellPriceCny <= 0.0 -> "请填写卖出价，或关掉「已卖出」开关"
        sellAmountCny == null || sellAmountCny <= 0.0 -> "请填写卖出金额"
        sellFeeValue < 0.0 -> "卖出手续费不能为负"
        sellBeforeBuy -> "卖出日期不能早于买入日期"
        else -> null
    }
    val canSave = problem == null

    // 预览用的记录，和真正落库的字段完全一致（价 / 金额均为人民币）
    val previewTrade = Trade(
        id = existing?.id ?: 0L,
        stockId = stockId,
        buyPrice = buyPriceCny ?: 0.0,
        buyAmount = buyAmountCny ?: 0.0,
        buyFee = buyFeeValue,
        buyTime = buyTimeText,
        sellPrice = if (hasSell) sellPriceCny else null,
        sellAmount = if (hasSell) sellAmountCny else null,
        sellFee = if (hasSell) sellFeeValue else null,
        sellTime = if (hasSell) sellTimeText else null,
        createdAt = existing?.createdAt ?: "",
        inputCurrency = currency,
        buyFxRate = if (isHkd) buyFx else 0.0,
        sellFxRate = if (isHkd && hasSell) sellFx else 0.0,
        buyPriceOriginal = if (isHkd) buyPrice.toDoubleOrNull() else null,
        buyAmountOriginal = if (isHkd) buyAmount.toDoubleOrNull() else null,
        sellPriceOriginal = if (isHkd && hasSell) sellPrice.toDoubleOrNull() else null,
        sellAmountOriginal = if (isHkd && hasSell) sellAmount.toDoubleOrNull() else null
    )

    /** 切币种时把已填数字按汇率互转，保持「这笔成交」本身不变；手续费是人民币，不动。 */
    fun switchCurrency(next: Currency) {
        if (next == currency || !supportsHkd) return
        val bfx = buyFxText.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val sfx = sellFxText.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
        fun convert(text: String, fx: Double): String {
            val value = text.toDoubleOrNull() ?: return text
            return numberText(if (next == Currency.HKD) value / fx else value * fx)
        }
        buyPrice = convert(buyPrice, bfx)
        buyAmount = convert(buyAmount, bfx)
        sellPrice = convert(sellPrice, sfx)
        sellAmount = convert(sellAmount, sfx)
        currency = next
    }

    // ---------------------------------------------------------------- 日期选择
    datePickerFor?.let { side ->
        val initial = if (side == 0) buyDate else sellDate
        val state = rememberDatePickerState(
            initialSelectedDateMillis = localDateToPickerMillis(initial)
        )
        DatePickerDialog(
            onDismissRequest = { datePickerFor = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked ->
                        val local = pickerMillisToLocalDate(picked)
                        if (side == 0) buyDate = local else sellDate = local
                    }
                    datePickerFor = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { datePickerFor = null }) { Text("取消") }
            }
        ) {
            DatePicker(state = state)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (editing) "编辑做T记录" else "添加做T记录",
                        style = MaterialTheme.typography.headlineMedium
                    )
                },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "$stockName · ${stockType.label}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ------------------------------------------------ 币种（仅港股通）
            if (supportsHkd) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "按哪种币种填写价格 / 金额",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (currency == Currency.CNY) {
                        Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("人民币 ¥") }
                    } else {
                        OutlinedButton(
                            onClick = { switchCurrency(Currency.CNY) },
                            modifier = Modifier.weight(1f)
                        ) { Text("人民币 ¥") }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (currency == Currency.HKD) {
                        Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("港币 HK$") }
                    } else {
                        OutlinedButton(
                            onClick = { switchCurrency(Currency.HKD) },
                            modifier = Modifier.weight(1f)
                        ) { Text("港币 HK$") }
                    }
                }
                Text(
                    text = if (isHkd) {
                        "按港币填，入库前按下面的结算汇率折成人民币；手续费、卡片、统计、盈利仍全部按人民币算。"
                    } else {
                        "按人民币填（和以前一样）。切到港币可按港股报价直接录入。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val unit = if (isHkd) "港元" else "元"

            // ---------------------------------------------------- 买入
            SectionTitle("买入")

            if (isHkd) {
                FxField(
                    label = "买入折算汇率（1 港币 = ? 元）",
                    value = buyFxText,
                    info = buyFxInfo.ifBlank { "按买入日期自动带出（表外日期联网补齐），可改成交割单上的值" },
                    onValueChange = {
                        buyFxText = it
                        buyFxManual = true
                        buyFxInfo = "已手工修改"
                    },
                    onReload = { buyFxManual = false; buyFxRefresh++ }
                )
                Text(
                    text = "交易所口径：买入港股按当日「卖出结算汇兑比率」付出人民币。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
            }

            NumberField("买入价（$unit）", buyPrice, "例如: 380.5") { buyPrice = it }

            // 买入价下面的小备注：建议卖出价
            if (suggestion != null) {
                val suggestedLabel = if (isHkd && sellFx > 0.0) {
                    "${priceText(suggestion.suggestedPrice / sellFx)} 港元（≈ ¥${priceText(suggestion.suggestedPrice)}）"
                } else {
                    "${priceText(suggestion.suggestedPrice)} 元"
                }
                Text(
                    text = "建议卖出价 $suggestedLabel" +
                        "　·　到手 ${feeConfig.targetLabel(stockType)}（已扣双边手续费，不免5）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
                TextButton(
                    onClick = {
                        // 金额写进输入框会被压到 3 位小数，手续费也按这个最终金额重算一次，
                        // 保证「价格 × 股数 − 手续费」和页面显示的预计盈利对得上。
                        val priceCny = suggestion.suggestedPrice
                        val priceInField = if (isHkd && sellFx > 0.0) priceCny / sellFx else priceCny
                        val amountCny = suggestion.sellAmount
                        val amountInField = if (isHkd && sellFx > 0.0) amountCny / sellFx else amountCny
                        val priceTextValue = numberText(priceInField)
                        val amountTextValue = numberText(amountInField)
                        val finalAmountCny = toCny(amountTextValue, sellFx) ?: amountCny
                        hasSell = true
                        sellPrice = priceTextValue
                        sellAmount = amountTextValue
                        sellFee = numberText(
                            FeeCalculator.sellFee(stockType, finalAmountCny, feeConfig, stockCode, sellFeeFx)
                        )
                        // 这是程序按费率填的，不是用户手改，保持自动模式
                        autoSellFee = true
                    },
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Text("按建议价填入卖出", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(
                    text = "填好买入价和买入金额后会给出建议卖出价。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                )
            }

            NumberField("买入金额（$unit）", buyAmount, "例如: 38050") { buyAmount = it }
            if (isHkd && buyAmountCny != null && buyAmountCny > 0.0) {
                Text(
                    text = "折合人民币 ¥${money2(buyAmountCny)}，手续费按这个人民币成交额算。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
            }
            NumberField("买入手续费（元）", buyFee, "按${stockType.label}费率自动算，可修改") {
                buyFee = it
                autoBuyFee = false
            }
            FxFeeTip(buyFeeFx, "买入")
            FeeAutoTip(
                auto = autoBuyFee,
                typeLabel = stockType.label,
                sideLabel = "买入",
                onRecalculate = { autoBuyFee = true }
            )
            TapField("买入日期", formatDate(buyDate)) { datePickerFor = 0 }

            Spacer(modifier = Modifier.height(20.dp))

            // ---------------------------------------------------- 卖出
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "卖出",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (hasSell) "已卖出" else "还没卖，记为未出T",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Switch(checked = hasSell, onCheckedChange = { hasSell = it })
            }

            if (hasSell) {
                Spacer(modifier = Modifier.height(8.dp))
                if (isHkd) {
                    FxField(
                        label = "卖出折算汇率（1 港币 = ? 元）",
                        value = sellFxText,
                        info = sellFxInfo.ifBlank { "按卖出日期自动带出（表外日期联网补齐），可改成交割单上的值" },
                        onValueChange = {
                            sellFxText = it
                            sellFxManual = true
                            sellFxInfo = "已手工修改"
                        },
                        onReload = { sellFxManual = false; sellFxRefresh++ }
                    )
                    Text(
                        text = "交易所口径：卖出港股按当日「买入结算汇兑比率」收到人民币；跨日做T和买入侧可以不同。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )
                }
                NumberField("卖出价（$unit）", sellPrice, "例如: 392") { sellPrice = it }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "卖出价、卖出金额、卖出手续费、卖出日期会在「已卖出」打开后出现。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 卖出价下面的小备注：预计盈利（按上面的建议卖出价算，人民币口径）
            if (suggestion != null) {
                Text(
                    text = "按建议卖出价预计盈利 ${signedMoney(suggestion.profit)}" +
                        "　·　已扣买卖手续费（人民币）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                )
            }

            if (hasSell) {
                NumberField("卖出金额（$unit）", sellAmount, "例如: 39200") { sellAmount = it }
                if (isHkd && sellAmountCny != null && sellAmountCny > 0.0) {
                    Text(
                        text = "折合人民币 ¥${money2(sellAmountCny)}，手续费按这个人民币成交额算。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )
                }
                NumberField("卖出手续费（元）", sellFee, "按${stockType.label}费率自动算，可修改") {
                    sellFee = it
                    autoSellFee = false
                }
                FxFeeTip(sellFeeFx, "卖出")
                FeeAutoTip(
                    auto = autoSellFee,
                    typeLabel = stockType.label,
                    sideLabel = "卖出",
                    onRecalculate = { autoSellFee = true }
                )
                TapField("卖出日期", formatDate(sellDate)) { datePickerFor = 1 }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ---------------------------------------------------- 预览
            SectionTitle("这条记录长这样（人民币）")
            TradeCard(
                trade = previewTrade,
                now = nowMillis,
                showActions = false,
                onEditClick = {},
                onDeleteClick = {}
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (problem != null) {
                Text(
                    text = problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    if (!canSave) return@Button
                    val trade = previewTrade.copy(
                        createdAt = existing?.createdAt ?: nowStamp()
                    )
                    if (editing) {
                        databaseHelper.updateTrade(trade)
                    } else {
                        databaseHelper.insertTrade(trade)
                    }
                    onBackClick()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSave
            ) {
                Text(text = if (editing) "保存修改" else "添加记录")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------------- 小组件

@Composable
private fun SectionTitle(text: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Divider(color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    )
}

/** 按日期自动带出的结算汇率：可手改，也能一键重新按日期取。 */
@Composable
private fun FxField(
    label: String,
    value: String,
    info: String,
    onValueChange: (String) -> Unit,
    onReload: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = info,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onReload) {
            Text("按日期重取", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 汇率输入框下面的来源说明：写清楚是本地表命中、自动补入了几天，
 * 还是交易所尚未公布 / 联网失败后回退到最近交易日。
 */
private fun describeFxLookup(found: FxLookup, sync: FxSyncResult?): String = buildString {
    if (sync != null && sync.added > 0) {
        append("已自动补入 ${sync.added} 个交易日，")
    }
    append(
        if (found.exact) "自动取 ${prettyDateKey(found.effectiveDate)} 的结算汇兑比率"
        else "该日无数据，用最近交易日 ${prettyDateKey(found.effectiveDate)}"
    )
    when {
        sync?.error != null -> append("（联网未成功：${sync.error}，暂用本地数据）")
        sync != null && sync.added == 0 -> append("（交易所尚未公布该日数据）")
    }
    when {
        found.note == FX_NOTE_EXCHANGE -> append("（交易所自动补入）")
        found.fromUser -> append("（你自己录入的）")
    }
}

/** 港股通印花税取整用的真实结算汇率，显示出来方便和交割单核对。 */
@Composable
private fun FxFeeTip(fx: Double, sideLabel: String) {
    if (fx > 0.0) {
        Text(
            text = "${sideLabel}印花税按整港币取整，结算汇率 ${fxText(fx)}（按${sideLabel}日期自动带出）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
    }
}

/** 手续费是自动算还是手改过；手改过就给个一键恢复自动的入口。 */
@Composable
private fun FeeAutoTip(
    auto: Boolean,
    typeLabel: String,
    sideLabel: String,
    onRecalculate: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (auto) {
                "${sideLabel}手续费：按${typeLabel}费率自动计算（可手改）"
            } else {
                "${sideLabel}手续费：已手工修改"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (!auto) {
            TextButton(onClick = onRecalculate) {
                Text("按费率重算", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 只读的日期选择框。
 *
 * 这里用一层透明 Box 盖在输入框上接点击，而不是给输入框挂 clickable ——
 * 后者会和输入框自身的焦点处理抢事件，点了没反应。
 */
@Composable
private fun TapField(
    label: String,
    value: String,
    onOpen: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            singleLine = true,
            trailingIcon = {
                Icon(imageVector = Icons.Default.DateRange, contentDescription = null)
            },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(onClick = onOpen)
        )
    }
}

// ---------------------------------------------------------------------- 小工具

private fun money2(value: Double): String = String.format(Locale.US, "%,.2f", value)

/** 去掉多余小数位：1000.0 -> "1000"，27.90 -> "27.9" */
private fun numberText(value: Double): String =
    String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

/**
 * Material3 的日期选择器给的是「UTC 当天 0 点」，
 * 直接按本地时区解读会在 UTC 以西的时区差一天。这里显式做一次换算。
 */
private fun localDateToPickerMillis(localMillis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMillis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(
            local.get(Calendar.YEAR),
            local.get(Calendar.MONTH),
            local.get(Calendar.DAY_OF_MONTH)
        )
    }.timeInMillis
}

private fun pickerMillisToLocalDate(pickerMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = pickerMillis
    }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}
