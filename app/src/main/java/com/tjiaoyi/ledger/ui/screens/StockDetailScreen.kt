package com.tjiaoyi.ledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tjiaoyi.ledger.data.db.DatabaseHelper
import com.tjiaoyi.ledger.data.model.Stock
import com.tjiaoyi.ledger.data.model.StockStat
import com.tjiaoyi.ledger.data.model.Stats
import com.tjiaoyi.ledger.data.model.Trade
import com.tjiaoyi.ledger.data.model.parseDateOrNull
import com.tjiaoyi.ledger.ui.components.TradeCard
import com.tjiaoyi.ledger.ui.components.money
import com.tjiaoyi.ledger.ui.components.percent
import com.tjiaoyi.ledger.ui.components.profitColor
import com.tjiaoyi.ledger.ui.components.signedMoney
import com.tjiaoyi.ledger.ui.theme.BarChartIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    stockId: Long,
    databaseHelper: DatabaseHelper,
    onBackClick: () -> Unit,
    onEditStockClick: (Long) -> Unit,
    onAddTradeClick: (Long) -> Unit,
    onEditTradeClick: (Long) -> Unit,
    onStatisticsClick: (Long) -> Unit,
    /** true = 总账户的合并视图：把同代码的多只股票记录合在一起看，只读，不能增删改 */
    groupMode: Boolean = false
) {
    // 总账户下按代码把多个分账户的股票合成一组；普通详情就只有自己这一只
    val groupIds = remember(stockId, groupMode) {
        if (groupMode) databaseHelper.getGroupStockIds(stockId) else listOf(stockId)
    }
    val ownerNames = remember(groupIds) {
        val accounts = databaseHelper.getAllAccounts().associate { it.id to it.name }
        databaseHelper.getAllStocks()
            .filter { it.id in groupIds }
            .associate { it.id to (accounts[it.accountId] ?: "") }
    }

    var stock by remember { mutableStateOf(databaseHelper.getStockById(stockId)) }
    var openTrades by remember { mutableStateOf(databaseHelper.getOpenTrades(stockId)) }
    var closedTrades by remember { mutableStateOf(databaseHelper.getClosedTrades(stockId)) }
    var stat by remember { mutableStateOf(Stats.ofStock(stockId, databaseHelper.getTradesByStockId(stockId))) }
    var deleteStockDialog by remember { mutableStateOf(false) }
    var deleteTradeTarget by remember { mutableStateOf<Trade?>(null) }

    fun refresh() {
        stock = databaseHelper.getStockById(stockId)
        openTrades = groupIds.flatMap { databaseHelper.getOpenTrades(it) }
            .sortedWith(
                compareBy<Trade> { it.buyPrice }
                    .thenBy { parseDateOrNull(it.buyTime) ?: 0L }
                    .thenBy { it.id }
            )
        closedTrades = groupIds.flatMap { databaseHelper.getClosedTrades(it) }
            .sortedWith(
                compareByDescending<Trade> { parseDateOrNull(it.sellTime) ?: 0L }
                    .thenByDescending { parseDateOrNull(it.buyTime) ?: 0L }
                    .thenByDescending { it.id }
            )
        stat = Stats.ofStock(stockId, groupIds.flatMap { databaseHelper.getTradesByStockId(it) })
    }

    LaunchedEffect(Unit) { refresh() }

    if (stock == null) {
        onBackClick()
        return
    }

    val current = stock ?: return
    val now = System.currentTimeMillis()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = current.name + if (groupMode) "（汇总）" else "",
                        style = MaterialTheme.typography.headlineMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onStatisticsClick(stockId) }) {
                        Icon(imageVector = BarChartIcon, contentDescription = "该股统计")
                    }
                    if (!groupMode) {
                        IconButton(onClick = { onEditStockClick(stockId) }) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = "编辑股票")
                        }
                        IconButton(onClick = { deleteStockDialog = true }) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "删除股票")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            if (!groupMode) {
                FloatingActionButton(
                    onClick = { onAddTradeClick(stockId) },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "添加做T记录",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "header") {
                Column {
                    StockHeaderCard(stock = current, stat = stat)
                    if (groupMode) {
                        Text(
                            text = "这是总账户汇总视图，合并了 ${groupIds.size} 个分账户里同代码的股票；" +
                                "如需修改记录，请切到对应分账户。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                        )
                    }
                }
            }

            if (openTrades.isEmpty() && closedTrades.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📋", style = MaterialTheme.typography.headlineLarge)
                            Text(
                                text = "还没有做T记录",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Text(
                                text = "点击右下角的 + 按钮记录第一笔买入",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------------- 第一档：未出T（买入价从低到高）
            if (openTrades.isNotEmpty()) {
                item(key = "open_header") {
                    SectionHeader(
                        title = "未出T",
                        count = openTrades.size,
                        hint = "按买入价从低到高"
                    )
                }
                items(openTrades, key = { "open_${it.id}" }) { trade ->
                    if (groupMode) OwnerLabel(ownerNames[trade.stockId])
                    TradeCard(
                        trade = trade,
                        now = now,
                        showActions = !groupMode,
                        onEditClick = { onEditTradeClick(trade.id) },
                        onDeleteClick = { deleteTradeTarget = trade }
                    )
                }
            }

            // ---------------------------------------------------- 第二档：已T（卖出时间从近到远）
            if (closedTrades.isNotEmpty()) {
                item(key = "closed_header") {
                    SectionHeader(
                        title = "已T",
                        count = closedTrades.size,
                        hint = "按卖出时间从近到远"
                    )
                }
                items(closedTrades, key = { "closed_${it.id}" }) { trade ->
                    if (groupMode) OwnerLabel(ownerNames[trade.stockId])
                    TradeCard(
                        trade = trade,
                        now = now,
                        showActions = !groupMode,
                        onEditClick = { onEditTradeClick(trade.id) },
                        onDeleteClick = { deleteTradeTarget = trade }
                    )
                }
            }

            item(key = "bottom_space") { Spacer(modifier = Modifier.height(72.dp)) }
        }
    }

    if (deleteStockDialog) {
        AlertDialog(
            onDismissRequest = { deleteStockDialog = false },
            title = { Text("确认删除") },
            text = {
                Text("确定要删除「${current.name}」吗？相关的 ${stat.totalCount} 条做T记录也会被删除。")
            },
            confirmButton = {
                TextButton(onClick = {
                    databaseHelper.deleteStock(stockId)
                    deleteStockDialog = false
                    onBackClick()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteStockDialog = false }) { Text("取消") }
            }
        )
    }

    deleteTradeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTradeTarget = null },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条${if (target.isClosed) "已T" else "未出T"}记录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    databaseHelper.deleteTrade(target.id)
                    deleteTradeTarget = null
                    refresh()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTradeTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun StockHeaderCard(stock: Stock, stat: StockStat) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stock.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (stock.code.isNotBlank()) {
                    Text(
                        text = "  ${stock.code}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                HeaderCell("盈利", signedMoney(stat.profit), profitColor(stat.profit), Modifier.weight(1f))
                HeaderCell("占用资金", money(stat.occupied), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                HeaderCell("近一年巅峰占用", money(stat.peakOccupied), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                HeaderCell("年利率", percent(stat.annualRate), profitColor(stat.annualRate ?: 0.0), Modifier.weight(1f))
                HeaderCell("胜率", percent(stat.winRate, digits = 0), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "未出T ${stat.openCount} 笔 · 已T ${stat.closedCount} 笔",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HeaderCell(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

/** 合并视图里给每笔记录标一下它来自哪个分账户。 */
@Composable
private fun OwnerLabel(name: String?) {
    if (name.isNullOrBlank()) return
    Text(
        text = name,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun SectionHeader(title: String, count: Int, hint: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = " $count 笔",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
