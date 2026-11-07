package com.tjiaoyi.ledger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tjiaoyi.ledger.data.db.DatabaseHelper
import com.tjiaoyi.ledger.data.model.ALL_ACCOUNTS_ID
import com.tjiaoyi.ledger.data.model.AppState
import com.tjiaoyi.ledger.data.model.MonthBucket
import com.tjiaoyi.ledger.data.model.Stock
import com.tjiaoyi.ledger.data.model.Stats
import com.tjiaoyi.ledger.data.model.TimeFilter
import com.tjiaoyi.ledger.data.model.Trade
import com.tjiaoyi.ledger.data.model.WindowStat
import com.tjiaoyi.ledger.data.model.stockGroupKey
import com.tjiaoyi.ledger.data.model.formatDate
import com.tjiaoyi.ledger.data.model.label
import com.tjiaoyi.ledger.data.model.monthOf
import com.tjiaoyi.ledger.data.model.parseDateOrNull
import com.tjiaoyi.ledger.data.model.rateLabel
import com.tjiaoyi.ledger.data.model.resolve
import com.tjiaoyi.ledger.data.model.yearOf
import com.tjiaoyi.ledger.ui.components.money
import com.tjiaoyi.ledger.ui.components.percent
import com.tjiaoyi.ledger.ui.components.profitColor
import com.tjiaoyi.ledger.ui.components.signedMoney
import java.util.Locale

/**
 * 统计页。
 *
 * - 首页右上角进去：统计全部股票
 * - 股票详情页右上角进去：只统计那一只
 *
 * 顶部可以选统计区间：近一月 / 近一年 / 近半年 / 近三月 / 全部，或者指定某一年、某一个月。
 *
 * 口径（都只看所选区间）：
 * - 盈利 = 区间内**卖出**的已T记录盈利合计
 * - 巅峰占用资金 = 区间内逐日统计「当天还没卖出的买入成本合计」的最大值，含跨期持仓
 * - 收益率 = 区间内已T盈利 ÷ 区间巅峰占用资金；窗口是整年时叫年利率
 * - 占用资金 = 当前未出T记录的买入金额 + 买入手续费（当下状态，不随区间变）
 * - 胜率 = 区间内已T中盈利笔数 ÷ 区间内已T笔数
 *
 * 区间说明下面还有一张「月度盈利」柱状图：把区间内的已T盈利按**卖出月份**拆开，
 * 逐月一根柱子，有盈有亏，各月之和正好等于上面的盈利。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    stockId: Long,
    databaseHelper: DatabaseHelper,
    onBackClick: () -> Unit,
    /** true = 从总账户的合并详情进来，统计这只股票在所有分账户里的记录 */
    groupMode: Boolean = false
) {
    var stocks by remember { mutableStateOf(emptyList<Stock>()) }
    var tradesByStock by remember { mutableStateOf(emptyMap<Long, List<Trade>>()) }
    var filter by remember { mutableStateOf<TimeFilter>(TimeFilter.Default) }
    var showPicker by remember { mutableStateOf(false) }
    var scopeLabel by remember { mutableStateOf("全部股票") }

    val selectedAccountId = AppState.selectedAccountId

    fun refresh() {
        val all = databaseHelper.getAllStocks()
        val accountNames = databaseHelper.getAllAccounts().associate { it.id to it.name }
        stocks = when {
            stockId > 0L && groupMode -> {
                val ids = databaseHelper.getGroupStockIds(stockId)
                all.filter { it.id in ids }
            }
            stockId > 0L -> all.filter { it.id == stockId }
            selectedAccountId == ALL_ACCOUNTS_ID -> all
            else -> all.filter { it.accountId == selectedAccountId }
        }
        scopeLabel = when {
            stockId > 0L -> stocks.firstOrNull()?.name ?: "该股"
            selectedAccountId == ALL_ACCOUNTS_ID -> "全部股票"
            else -> accountNames[selectedAccountId] ?: "该账户"
        }
        tradesByStock = databaseHelper.getAllTradesGroupedByStock()
    }

    LaunchedEffect(stockId, groupMode, selectedAccountId) { refresh() }

    val singleMode = stockId > 0L
    val now = System.currentTimeMillis()

    // 当前范围内的全部记录，用来算组合级的巅峰占用
    val scopedTrades = stocks.flatMap { tradesByStock[it.id].orEmpty() }
    val earliest = scopedTrades.mapNotNull { parseDateOrNull(it.buyTime) }.minOrNull()
    val range = filter.resolve(now, earliest)

    val overall = Stats.window(scopedTrades, range, now)

    // 月度盈利柱状图：和 overall.profit 同口径，各月之和正好等于它
    val monthly = Stats.monthlyProfit(scopedTrades, range, now)

    // 总账户下按代码合并明细：同一只股票在两个账户里各有一条时只显示一行，数值相加
    val groups: List<Pair<Stock, List<Trade>>> =
        if (!singleMode && selectedAccountId == ALL_ACCOUNTS_ID) {
            stocks.groupBy { stockGroupKey(it) }.map { (_, members) ->
                members.first() to members.flatMap { tradesByStock[it.id].orEmpty() }
            }
        } else {
            stocks.map { it to tradesByStock[it.id].orEmpty() }
        }
    val perStock: Map<Long, WindowStat> = groups.associate { (stock, trades) ->
        stock.id to Stats.window(trades, range, now)
    }

    // 排行榜按区间盈利从高到低
    val ranked = groups.sortedByDescending { perStock[it.first.id]?.profit ?: 0.0 }

    // 可选的年份 / 月份：从数据里的日期推出来
    val dateMillis = scopedTrades.flatMap {
        listOfNotNull(parseDateOrNull(it.buyTime), parseDateOrNull(it.sellTime))
    }
    val years = (dateMillis.map { yearOf(it) } + yearOf(now)).distinct().sortedDescending()
    val monthsByYear = dateMillis.groupBy { yearOf(it) }
        .mapValues { (_, values) -> values.map { monthOf(it) }.toSet() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (singleMode) "该股统计" else "统计",
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
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            FilterBar(
                filter = filter,
                rangeText = "${formatDate(range.startMillis)} ~ ${formatDate(range.endMillis)}",
                onQuickPick = { filter = it },
                onOpenPicker = { showPicker = true }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "summary") {
                    SummaryCard(
                        title = scopeLabel,
                        stat = overall,
                        rateLabel = filter.rateLabel,
                        subtitle = "共 ${groups.size} 只股票 · 已T ${overall.closedCount} 笔 · 未出T ${overall.openCount} 笔"
                    )
                }

                item(key = "caption") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = "口径说明：盈利只计区间内卖出的已T记录；" +
                                "巅峰占用资金 = 区间内逐日统计「当天还没卖出的买入成本合计」的最大值，含跨期持仓，" +
                                "未卖出的记到今天；" +
                                "收益率 = 区间内已T盈利 ÷ 区间巅峰占用资金，窗口正好是整年时才叫年利率；" +
                                "占用资金 = 当前未出T记录的买入金额加买入手续费，是当下状态、不随区间变；" +
                                "胜率 = 区间内已T中盈利笔数 ÷ 区间已T笔数。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                item(key = "monthly_chart") {
                    MonthlyProfitChart(monthly)
                }

                if (!singleMode) {
                    item(key = "list_header") {
                        Text(
                            text = "各股票明细（按区间盈利排序）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    if (ranked.isEmpty()) {
                        item(key = "list_empty") {
                            Text(
                                text = "还没有股票，先去首页添加一只吧。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    items(ranked, key = { "stock_${it.first.id}" }) { (stock, _) ->
                        StockStatRow(
                            stock = stock,
                            stat = perStock[stock.id] ?: WindowStat(),
                            rateLabel = filter.rateLabel
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        TimePickerDialog(
            filter = filter,
            years = years,
            monthsByYear = monthsByYear,
            onPick = { filter = it },
            onDismiss = { showPicker = false }
        )
    }
}

/** 顶部的时间筛选条。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    filter: TimeFilter,
    rangeText: String,
    onQuickPick: (TimeFilter) -> Unit,
    onOpenPicker: () -> Unit
) {
    val quick = listOf(
        TimeFilter.LastMonth,
        TimeFilter.LastYear,
        TimeFilter.LastHalfYear,
        TimeFilter.LastQuarter,
        TimeFilter.All
    )
    val pickedYearOrMonth = filter is TimeFilter.Year || filter is TimeFilter.Month

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)
    ) {
        Text(
            text = "统计区间：${filter.label} · $rangeText",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            quick.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { onQuickPick(option) },
                    label = { Text(option.label) }
                )
            }
            FilterChip(
                selected = pickedYearOrMonth,
                onClick = onOpenPicker,
                label = { Text(if (pickedYearOrMonth) filter.label else "按年/月") }
            )
        }
    }
}

/** 选年份 / 月份。点一下立即生效，弹窗不关，方便连着试几个区间。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TimePickerDialog(
    filter: TimeFilter,
    years: List<Int>,
    monthsByYear: Map<Int, Set<Int>>,
    onPick: (TimeFilter) -> Unit,
    onDismiss: () -> Unit
) {
    var pickedYear by remember {
        mutableStateOf(
            when (filter) {
                is TimeFilter.Year -> filter.year
                is TimeFilter.Month -> filter.year
                else -> years.firstOrNull() ?: yearOf(System.currentTimeMillis())
            }
        )
    }
    val monthsWithData = monthsByYear[pickedYear].orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择统计区间") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "当前：${filter.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "按年份",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    years.forEach { year ->
                        FilterChip(
                            selected = pickedYear == year,
                            onClick = {
                                pickedYear = year
                                onPick(TimeFilter.Year(year))
                            },
                            label = { Text("${year}年") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "按月份（${pickedYear}年）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                // 12 个月自动换行铺开，不用横向找
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    (1..12).forEach { month ->
                        val hasData = month in monthsWithData
                        FilterChip(
                            selected = filter is TimeFilter.Month &&
                                filter.year == pickedYear &&
                                filter.month == month,
                            enabled = hasData,
                            onClick = { onPick(TimeFilter.Month(pickedYear, month)) },
                            label = { Text("${month}月") }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "灰色的月份没有记录。选完点「关闭」即可，数据会实时跟着变。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/**
 * 月度盈利柱状图。
 *
 * 横轴是统计区间里的**每一个自然月**，纵轴是当月卖出的已T盈利：红柱朝上是赚、绿柱朝下是亏，
 * 中间那条线是 0。归月规则和顶部卡片的「盈利」一个口径（按卖出日期、只算区间内的已T），
 * 所以各月相加正好等于那个数。
 *
 * 三件事决定它好不好读：
 * - **柱子上直接标金额**。格子宽就写「+¥1.5万」，窄一点写「+1.5万」，再窄只写数字，
 *   挤到连数字都放不下才干脆不写、靠点柱子看读数。降级规则按估出来的文字宽度和格子宽度比，
 *   所以柱子越多、字越少，不会出现截断成「+¥1.5」那种半截数。
 * - **一笔没卖的月份在零线上留个灰点、并标个 0**，而不是留一片空白。空白会让人以为图坏了、
 *   这个月被漏掉了；灰点说的是「这一格在，只是这个月没有卖出的已T」。
 *   卖了但盈亏相抵正好 0 的月份只有 0、没有点，两种情况分得开。
 * - 上下两个区间的高度按 |最大盈利| : |最大亏损| 分配，零基线因此在任何数据下都落在同一高度；
 *   每列都单独画那一条 1dp 的线，拼起来就是一条连续的轴。标金额时两侧各保底留一行字的高度，
 *   否则全是亏损的区间里零线会顶到最上面、空月那个 0 也没地方写。
 */
@Composable
private fun MonthlyProfitChart(buckets: List<MonthBucket>) {
    val count = buckets.size
    var selected by remember(buckets) { mutableStateOf<Int?>(null) }
    val shown = selected?.takeIf { it in buckets.indices }?.let { buckets[it] }

    val maxPos = buckets.maxOf { if (it.profit > 0.0) it.profit else 0.0 }
    val maxNeg = buckets.maxOf { if (it.profit < 0.0) -it.profit else 0.0 }
    val total = buckets.sumOf { it.profit }
    // 月份标签按柱子数量抽稀，别挤成一团
    val labelStep = when {
        count <= 13 -> 1
        count <= 26 -> 2
        count <= 52 -> 3
        else -> 6
    }
    val axisStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
    val valueStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium)
    // 同一批柱子先按两种写法各排一遍，进布局后挑格子放得下的那一种
    val fullLabels = buckets.map { chartValueLabel(it.profit, symbol = true) }
    val plainLabels = buckets.map { chartValueLabel(it.profit, symbol = false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "月度盈利",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                if (count > 0) {
                    Text(
                        text = "${buckets.first().year}·${buckets.first().month}月 – " +
                            "${buckets.last().year}·${buckets.last().month}月",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            if (count == 0) {
                Text(
                    text = "这个区间里没有卖出的已T记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val plotHeight = 160.dp
                val axisHeight = 1.dp
                val valueHeight = 13.dp
                val zones = plotHeight - axisHeight
                // 零线的位置按 |最大盈利| : |最大亏损| 分配，所以它在任何数据下都是「真正的 0」
                val posShare = if (maxPos + maxNeg <= 0.0) {
                    0.5f
                } else {
                    (maxPos / (maxPos + maxNeg)).toFloat()
                }

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val slot = maxWidth / count
                    // 金额用哪种写法，看格子放不放得下：带 ¥ → 不带 ¥ → 不标。
                    // 宽度按字号 × 系统字体缩放估，字体调大的用户不会被切成「+26」半截数
                    val textScale = LocalDensity.current.fontScale
                    val room = slot.value * 1.12f
                    val tier = when {
                        fullLabels.maxOf { chartLabelWidthDp(it, 9f) } * textScale <= room -> 2
                        plainLabels.maxOf { chartLabelWidthDp(it, 9f) } * textScale <= room -> 1
                        else -> 0
                    }
                    val barWidth = (slot * 0.52f).coerceIn(4.dp, 22.dp)
                    // 只有真要标金额时才预留那一行；不标的时候让最高的柱子把区间用满
                    val labelSpace = if (tier > 0) valueHeight else 0.dp
                    // 标金额时两侧至少各留一行字的高度：全亏的区间里 posZone 会算成 0，
                    // 那样空月的 0、以及零线上方那点留白全被挤没了，零线也会顶到卡片最上面
                    val minZone = if (tier > 0) valueHeight else 0.dp
                    val posZone = (zones * posShare).coerceIn(minZone, zones - minZone)
                    val negZone = zones - posZone
                    val posBarMax = (posZone - labelSpace).coerceAtLeast(0.dp)
                    val negBarMax = (negZone - labelSpace).coerceAtLeast(0.dp)

                    Row(modifier = Modifier.fillMaxWidth().height(plotHeight)) {
                        buckets.forEachIndexed { index, bucket ->
                            val isSelected = index == selected
                            val profit = bucket.profit
                            val barHeight = when {
                                profit > 0.0 && maxPos > 0.0 ->
                                    posBarMax * (profit / maxPos).toFloat()
                                profit < 0.0 && maxNeg > 0.0 ->
                                    negBarMax * (-profit / maxNeg).toFloat()
                                else -> 0.dp
                            }
                            val barColor = profitColor(profit)
                            val valueText = when (tier) {
                                2 -> fullLabels[index]
                                1 -> plainLabels[index]
                                else -> null
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable {
                                        selected = if (isSelected) null else index
                                    }
                            ) {
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant
                                                    .copy(alpha = 0.55f)
                                            )
                                    )
                                }
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(posZone),
                                        contentAlignment = Alignment.BottomCenter
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            if (valueText != null && profit > 0.0) {
                                                Text(
                                                    text = valueText,
                                                    style = valueStyle,
                                                    color = barColor,
                                                    maxLines = 1
                                                )
                                            }
                                            // 只有赚的月份才在零线上方画柱子；反过来亏的那根也一样，
                                            // 两边都画会把同一根柱子复制到零线另一侧，看着像穿过了 0
                                            if (profit > 0.0) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(barWidth)
                                                        .height(barHeight)
                                                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                                        .background(barColor)
                                                )
                                            }
                                            // 盈亏相抵为 0 的月份：贴着零线写个 0，别让人以为少了一格
                                            if (valueText != null && profit == 0.0) {
                                                Text(
                                                    text = valueText,
                                                    style = valueStyle,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(axisHeight)
                                            .background(MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        // 这个月一笔没卖：在零线上留个灰点。轴只有 1dp 高，普通 size()
                                        // 会被这个约束压扁，requiredSize 才顶得出去（Compose 默认不裁剪子节点）
                                        if (bucket.closedCount == 0) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.Center)
                                                    .requiredSize(5.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.outline)
                                            )
                                        }
                                    }
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(negZone),
                                        contentAlignment = Alignment.TopCenter
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            if (profit < 0.0) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(barWidth)
                                                        .height(barHeight)
                                                        .clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
                                                        .background(barColor)
                                                )
                                            }
                                            if (valueText != null && profit < 0.0) {
                                                Text(
                                                    text = valueText,
                                                    style = valueStyle,
                                                    color = barColor,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                // 月份标签不各占一格：18 个月时一格只有 19dp，"10月" 会被挤成 "10"。
                // 这里让每个标签在自己的柱子上居中、向两边借用空格子，就不怕抽稀后变窄了。
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(15.dp)) {
                    val slotWidth = maxWidth / count
                    val labelWidth = 30.dp
                    buckets.forEachIndexed { index, bucket ->
                        if (index % labelStep != 0) return@forEachIndexed
                        val center = slotWidth * (index + 0.5f)
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset(
                                    x = (center - labelWidth / 2)
                                        .coerceIn(0.dp, (maxWidth - labelWidth).coerceAtLeast(0.dp))
                                )
                                .width(labelWidth)
                        ) {
                            Text(
                                text = "${bucket.month}月",
                                style = axisStyle,
                                color = if (index == selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                if (shown != null) {
                    Text(
                        text = "${shown.year}年${shown.month}月　盈利 ${signedMoney(shown.profit)}　" +
                            "已T ${shown.closedCount} 笔　胜率 ${percent(shown.winRate, digits = 0)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = profitColor(shown.profit)
                    )
                } else {
                    Text(
                        text = "合计 ${signedMoney(total)}　共 $count 个月　点柱子看单月",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 柱子上那行金额。位置（零线上方/下方）和颜色已经说明了盈亏，所以格子不够宽时
 * 可以去掉 ¥、再去掉正负号，只留个数；位数也一律收到能读就行（万/亿），
 * 精确金额在下面的读数行里。
 */
private fun chartValueLabel(value: Double, symbol: Boolean): String {
    if (value == 0.0) return "0"
    val sign = if (value > 0.0) "+" else "-"
    val yuan = if (symbol) "¥" else ""
    val absolute = kotlin.math.abs(value)
    return when {
        absolute >= 1e8 -> "$sign$yuan" + String.format(Locale.getDefault(), "%.2f亿", absolute / 1e8)
        absolute >= 1e4 -> "$sign$yuan" + String.format(Locale.getDefault(), "%.1f万", absolute / 1e4)
        else -> "$sign$yuan" + String.format(Locale.getDefault(), "%.0f", absolute)
    }
}

/**
 * 粗估一段文字排版后有多宽（dp），用来决定柱子上到底标不标金额。
 * 按字号的倍数算：全角字和 ¥ 各占 1em，数字和字母约 0.58em，正负号 0.55em，小数点更窄。
 * 只是拿来比个大小，不需要精确到像素；真放不下时文字自己会截，看图能发现。
 */
private fun chartLabelWidthDp(text: String, fontSizeSp: Float): Float {
    var units = 0f
    for (ch in text) {
        units += when {
            ch == '.' || ch == ',' || ch == '·' -> 0.33f
            ch == '+' || ch == '-' -> 0.55f
            ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' -> 0.58f
            else -> 1.0f
        }
    }
    return units * fontSizeSp
}

@Composable
private fun SummaryCard(title: String, stat: WindowStat, rateLabel: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                BigMetric(
                    label = "盈利",
                    value = signedMoney(stat.profit),
                    valueColor = profitColor(stat.profit),
                    modifier = Modifier.weight(1f)
                )
                BigMetric(
                    label = "巅峰占用资金",
                    value = money(stat.peakOccupied),
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            // 这一行有三格，字号收一档，金额才不会被截断
            Row(modifier = Modifier.fillMaxWidth()) {
                BigMetric(
                    label = rateLabel,
                    value = percent(stat.rate),
                    valueColor = profitColor(stat.rate ?: 0.0),
                    compact = true,
                    modifier = Modifier.weight(1f)
                )
                BigMetric(
                    label = "占用资金",
                    value = money(stat.occupied),
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    compact = true,
                    modifier = Modifier.weight(1f)
                )
                BigMetric(
                    label = "胜率",
                    value = percent(stat.winRate, digits = 0),
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    compact = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BigMetric(
    label: String,
    value: String,
    valueColor: Color,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            text = value,
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1
        )
    }
}

@Composable
private fun StockStatRow(stock: Stock, stat: WindowStat, rateLabel: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stock.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (stock.code.isNotBlank()) {
                    Text(
                        text = "  ${stock.code}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "已T ${stat.closedCount} · 未出T ${stat.openCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            MetricLine(
                label1 = "盈利", value1 = signedMoney(stat.profit), color1 = profitColor(stat.profit),
                label2 = "巅峰占用", value2 = money(stat.peakOccupied), color2 = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            MetricLine(
                label1 = rateLabel, value1 = percent(stat.rate), color1 = profitColor(stat.rate ?: 0.0),
                label2 = "胜率", value2 = percent(stat.winRate, digits = 0), color2 = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun MetricLine(
    label1: String,
    value1: String,
    color1: Color,
    label2: String,
    value2: String,
    color2: Color
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label1,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value1,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color1
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = label2,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp)
        )
        Text(
            text = value2,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color2
        )
    }
}
