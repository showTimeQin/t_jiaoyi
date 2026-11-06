package com.tjiaoyi.ledger.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tjiaoyi.ledger.data.db.DatabaseHelper
import com.tjiaoyi.ledger.data.model.ALL_ACCOUNTS_ID
import com.tjiaoyi.ledger.data.model.Account
import com.tjiaoyi.ledger.data.model.AppState
import com.tjiaoyi.ledger.data.model.Stock
import com.tjiaoyi.ledger.data.model.Stats
import com.tjiaoyi.ledger.data.model.StockStat
import com.tjiaoyi.ledger.data.model.stockGroupKey
import com.tjiaoyi.ledger.ui.components.StockCard
import com.tjiaoyi.ledger.ui.components.StockCardHeight
import com.tjiaoyi.ledger.ui.components.StockCardSpacing
import com.tjiaoyi.ledger.ui.theme.BarChartIcon
import com.tjiaoyi.ledger.ui.theme.DataTransferIcon
import kotlin.math.roundToInt

/** 首页的一张卡：可能是真实的一只股票，也可能是总账户把同代码股票合并出来的组。 */
private data class DisplayStock(
    val key: String,
    val primary: Stock,
    val ids: List<Long>,
    val isGroup: Boolean,
    val annotations: List<String>,
    val stat: StockStat
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    databaseHelper: DatabaseHelper,
    onStockClick: (Long) -> Unit,
    onGroupClick: (Long) -> Unit,
    onAddStockClick: () -> Unit,
    onEditStockClick: (Long) -> Unit,
    onStatisticsClick: () -> Unit,
    onDataTransferClick: () -> Unit,
    onAccountsClick: () -> Unit,
    onFeeSettingsClick: () -> Unit,
    onFxRatesClick: () -> Unit
) {
    val selectedAccountId = AppState.selectedAccountId
    val isAllAccounts = selectedAccountId == ALL_ACCOUNTS_ID

    var accounts by remember { mutableStateOf(databaseHelper.getAllAccounts()) }
    var display by remember { mutableStateOf(emptyList<DisplayStock>()) }

    var sortMode by remember { mutableStateOf(false) }
    var accountMenuOpen by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<DisplayStock?>(null) }
    var deleteTarget by remember { mutableStateOf<DisplayStock?>(null) }

    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }

    val density = LocalDensity.current
    // 卡片高度固定，拖动时按「卡片高 + 间距」换算成目标下标
    val pitch = with(density) { (StockCardHeight + StockCardSpacing).toPx() }

    fun loadDisplay(accountId: Long, accountList: List<Account>): List<DisplayStock> {
        val all = databaseHelper.getAllStocks(accountId)
        val grouped = databaseHelper.getAllTradesGroupedByStock()
        val now = System.currentTimeMillis()
        val accountNames = accountList.associate { it.id to it.name }

        return if (accountId == ALL_ACCOUNTS_ID) {
            // 总账户：按股票代码（没代码按名称）合并，盈利 / 占用 / 笔数全部相加
            all.groupBy { stockGroupKey(it) }.map { (key, members) ->
                val trades = members.flatMap { grouped[it.id].orEmpty() }
                val first = members.first()
                val annotations = buildList {
                    add(first.stockType.label)
                    if (members.size > 1) {
                        add("${members.size}个账户合并")
                    } else {
                        accountNames[first.accountId]?.let { add(it) }
                    }
                }
                DisplayStock(
                    key = "group_$key",
                    primary = first,
                    ids = members.map { it.id },
                    isGroup = true,
                    annotations = annotations,
                    stat = Stats.ofStock(first.id, trades, now)
                )
            }.sortedBy { it.primary.sortOrder }
        } else {
            all.map { stock ->
                DisplayStock(
                    key = "stock_${stock.id}",
                    primary = stock,
                    ids = listOf(stock.id),
                    isGroup = false,
                    annotations = listOf(stock.stockType.label),
                    stat = Stats.ofStock(stock.id, grouped[stock.id].orEmpty(), now)
                )
            }
        }
    }

    fun refresh() {
        accounts = databaseHelper.getAllAccounts()
        display = loadDisplay(selectedAccountId, accounts)
    }

    LaunchedEffect(selectedAccountId, AppState.accountsVersion) { refresh() }

    // ---------------------------------------------------------------- 长按菜单
    menuTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { menuTarget = null },
            title = {
                Text(
                    "${target.primary.name}" +
                        if (target.primary.code.isBlank()) "" else "  ${target.primary.code}"
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isAllAccounts) {
                        Text(
                            text = "总账户是只读汇总视图。想修改或删除这只股票，" +
                                "请从顶部下拉切到它所属的分账户。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        MenuAction(text = "修改股票信息") {
                            menuTarget = null
                            onEditStockClick(target.primary.id)
                        }
                        MenuAction(text = "调整在首页的顺序") {
                            menuTarget = null
                            sortMode = true
                        }
                        MenuAction(text = "删除", isDestructive = true) {
                            menuTarget = null
                            deleteTarget = target
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuTarget = null }) { Text("关闭") }
            }
        )
    }

    // ---------------------------------------------------------------- 删除确认
    deleteTarget?.let { target ->
        val count = target.stat.totalCount
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("确认删除") },
            text = {
                Text(
                    "确定要删除「${target.primary.name}」吗？" +
                        if (count > 0) "它的 $count 条做T记录也会一并删除，且无法恢复。" else "该股票还没有做T记录。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    databaseHelper.deleteStock(target.primary.id)
                    deleteTarget = null
                    refresh()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            if (sortMode) {
                TopAppBar(
                    title = { Text("调整顺序", style = MaterialTheme.typography.headlineMedium) },
                    actions = {
                        TextButton(onClick = { sortMode = false }) {
                            Text("完成", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Box {
                            Row(
                                modifier = Modifier.clickable { accountMenuOpen = true },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isAllAccounts) {
                                        "总账户"
                                    } else {
                                        accounts.firstOrNull { it.id == selectedAccountId }?.name ?: "总账户"
                                    },
                                    style = MaterialTheme.typography.headlineMedium
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "切换账户",
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }

                            DropdownMenu(
                                expanded = accountMenuOpen,
                                onDismissRequest = { accountMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("总账户（全部汇总）") },
                                    onClick = {
                                        AppState.selectAccount(ALL_ACCOUNTS_ID)
                                        sortMode = false
                                        accountMenuOpen = false
                                    }
                                )
                                accounts.forEach { account ->
                                    DropdownMenuItem(
                                        text = { Text(account.name) },
                                        onClick = {
                                            AppState.selectAccount(account.id)
                                            sortMode = false
                                            accountMenuOpen = false
                                        }
                                    )
                                }
                                Divider()
                                DropdownMenuItem(
                                    text = { Text("管理分账户") },
                                    onClick = {
                                        accountMenuOpen = false
                                        onAccountsClick()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("手续费费率设置") },
                                    onClick = {
                                        accountMenuOpen = false
                                        onFeeSettingsClick()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("港股通结算汇率表") },
                                    onClick = {
                                        accountMenuOpen = false
                                        onFxRatesClick()
                                    }
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onStatisticsClick) {
                            Icon(
                                imageVector = BarChartIcon,
                                contentDescription = "统计",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        IconButton(onClick = onDataTransferClick) {
                            Icon(
                                imageVector = DataTransferIcon,
                                contentDescription = "数据导入导出",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        },
        floatingActionButton = {
            if (!sortMode) {
                FloatingActionButton(
                    onClick = onAddStockClick,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "添加股票",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    ) { paddingValues ->
        if (display.isEmpty()) {
            EmptyState(
                accountLabel = if (isAllAccounts) "总账户" else "这个账户",
                modifier = Modifier.fillMaxSize().padding(paddingValues)
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(StockCardSpacing)
            ) {
                if (sortMode) {
                    item(key = "sort_hint") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(
                                text = "按住卡片上下拖动即可调整顺序，拖好后点右上角「完成」。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                itemsIndexed(display, key = { _, item -> item.key }) { _, item ->
                    val isDragging = draggingId == item.key

                    // 被拖的卡片浮在最上层，并跟随手指位移；其余卡片用默认的位置动画让位
                    val dragModifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }

                    val placeModifier =
                        if (isDragging) Modifier else Modifier.animateItemPlacement()

                    val gestureModifier = if (sortMode) {
                        Modifier.pointerInput(item.key) {
                            detectDragGestures(
                                onDragStart = {
                                    draggingId = item.key
                                    dragOffset = 0f
                                },
                                onDragEnd = {
                                    draggingId = null
                                    dragOffset = 0f
                                    databaseHelper.updateStockOrder(display.map { it.primary.id })
                                },
                                onDragCancel = {
                                    draggingId = null
                                    dragOffset = 0f
                                },
                                onDrag = { change, delta ->
                                    change.consume()
                                    dragOffset += delta.y

                                    val from = display.indexOfFirst { it.key == item.key }
                                    if (from < 0) return@detectDragGestures

                                    val shift = (dragOffset / pitch).roundToInt()
                                    val to = (from + shift).coerceIn(0, display.lastIndex)
                                    if (to != from) {
                                        display = display.toMutableList().apply {
                                            add(to, removeAt(from))
                                        }
                                        // 换过位置后把基准点挪到新卡片中心，避免连续跳动
                                        dragOffset -= (to - from) * pitch
                                    }
                                }
                            )
                        }
                    } else {
                        Modifier
                    }

                    StockCard(
                        stock = item.primary,
                        stat = item.stat,
                        isSortMode = sortMode,
                        isDragging = isDragging,
                        onClick = {
                            if (isAllAccounts) onGroupClick(item.primary.id)
                            else onStockClick(item.primary.id)
                        },
                        onLongClick = if (sortMode) null else ({ menuTarget = item }),
                        annotations = item.annotations,
                        modifier = dragModifier.then(placeModifier).then(gestureModifier)
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuAction(
    text: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EmptyState(accountLabel: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("📈", style = MaterialTheme.typography.headlineLarge)
            Text(
                text = "${accountLabel}还没有股票",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "点击右下角的 + 按钮创建第一只股票\n名称和代码自己填，之后点进去记录做T",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
