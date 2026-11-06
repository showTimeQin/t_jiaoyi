package com.tjiaoyi.ledger.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tjiaoyi.ledger.data.model.Stock
import com.tjiaoyi.ledger.data.model.StockStat
import com.tjiaoyi.ledger.ui.theme.DragHandleIcon
import com.tjiaoyi.ledger.ui.theme.ProfitDownColor
import com.tjiaoyi.ledger.ui.theme.ProfitUpColor

/** 首页卡片高度固定，拖动排序时才能按固定间距换算目标位置。 */
val StockCardHeight = 178.dp
val StockCardSpacing = 10.dp

/**
 * 首页的股票卡片。
 *
 * 布局：上排是股票名/代码 + 未出T、已T 笔数；
 * 虚线下方是全部/当前口径的盈利、占用资金、胜率；
 * 实线下方是近一年口径的近一年盈利、近一年巅峰占用、年利率（前者 ÷ 中者 = 后者）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StockCard(
    stock: Stock,
    stat: StockStat,
    isSortMode: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** 名称下面的一行小徽标：股票类型、账户名、合并了几张卡等 */
    annotations: List<String> = emptyList()
) {
    val clickModifier = when {
        isSortMode -> Modifier
        onLongClick != null -> Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        else -> Modifier
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(StockCardHeight)
            .then(clickModifier),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 8.dp else 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (isSortMode) {
                // 排序模式下左侧露出把手，提示这张卡片可以拖
                Column(
                    modifier = Modifier
                        .width(36.dp)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = DragHandleIcon,
                        contentDescription = "拖动排序",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(20.dp).height(20.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = if (isSortMode) 0.dp else 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1) 股票名 + 代码 / 未出T + 已T
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stock.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (stock.code.isNotBlank()) {
                                Text(
                                    text = stock.code,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            annotations.forEach { label ->
                                Spacer(modifier = Modifier.width(6.dp))
                                Badge(text = label)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(horizontalAlignment = Alignment.End) {
                        OpenBadge(count = stat.openCount)
                        Spacer(modifier = Modifier.height(4.dp))
                        ClosedBadge(count = stat.closedCount)
                    }
                }

                // 2) 虚线 + 全部/当前口径：盈利 / 占用资金 / 胜率
                Column(modifier = Modifier.fillMaxWidth()) {
                    DashedDivider()
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        StatCell(
                            label = "盈利",
                            value = signedMoney(stat.profit),
                            valueColor = profitColor(stat.profit),
                            modifier = Modifier.weight(1f)
                        )
                        StatCell(
                            label = "占用资金",
                            value = money(stat.occupied),
                            valueColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        StatCell(
                            label = "胜率",
                            value = percent(stat.winRate, digits = 0),
                            valueColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 3) 实线 + 近一年口径：近一年盈利 / 近一年巅峰占用 / 年利率
                //    三格排成一条，正好是年利率的算式：分子 ÷ 分母 = 结果，一眼能对上
                Column(modifier = Modifier.fillMaxWidth()) {
                    Divider(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        StatCell(
                            label = "近一年盈利",
                            value = signedMoney(stat.yearProfit),
                            valueColor = profitColor(stat.yearProfit),
                            modifier = Modifier.weight(1f)
                        )
                        StatCell(
                            label = "近一年巅峰占用",
                            value = money(stat.peakOccupied),
                            valueColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        StatCell(
                            label = "年利率",
                            value = percent(stat.annualRate),
                            valueColor = profitColor(stat.annualRate ?: 0.0),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // 卡片高度是固定的（拖动排序要按固定间距换算），标签一律单行，不许换行撑高
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 中国市场惯例：正数红、负数绿。 */
fun profitColor(value: Double): Color =
    if (value < 0.0) ProfitDownColor else ProfitUpColor
