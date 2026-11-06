package com.tjiaoyi.ledger.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tjiaoyi.ledger.data.db.DatabaseHelper
import com.tjiaoyi.ledger.data.model.ALL_ACCOUNTS_ID
import com.tjiaoyi.ledger.data.model.AppState
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

/**
 * 统计页。
 *
 * - 首页右上角进去：统计全部股票
 * - 股票详情页右上角进去：只统计那一只
 *
 * 顶部可以选统计区间：近一年 / 近半年 / 近三月 / 全部，或者指定某一年、某一个月。
 *
 * 口径（都只看所选区间）：
 * - 盈利 = 区间内**卖出**的已T记录盈利合计
 * - 巅峰占用资金 = 区间内逐日统计「当天还没卖出的买入成本合计」的最大值，含跨期持仓
 * - 收益率 = 区间内已T盈利 ÷ 区间巅峰占用资金；窗口是整年时叫年利率
 * - 占用资金 = 当前未出T记录的买入金额 + 买入手续费（当下状态，不随区间变）
 * - 胜率 = 区间内已T中盈利笔数 ÷ 区间内已T笔数
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
