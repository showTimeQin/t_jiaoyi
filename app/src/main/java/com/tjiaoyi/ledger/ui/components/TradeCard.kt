package com.tjiaoyi.ledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tjiaoyi.ledger.data.model.Currency
import com.tjiaoyi.ledger.data.model.Trade
import com.tjiaoyi.ledger.data.model.fxText
import com.tjiaoyi.ledger.ui.theme.ClosedBadgeColor
import com.tjiaoyi.ledger.ui.theme.DividerColor
import com.tjiaoyi.ledger.ui.theme.OpenBadgeColor

/**
 * 一条做T记录的卡片，布局照手绘图：
 *
 * ```
 * ┌──────────────────────────────────────────┐
 * │ [未出T]                          ✎   🗑   │
 * │   买入               │        卖出        │
 * │   价格   380.000     │   价格   392.000   │
 * │   金额   ¥38,000.00  │   金额   ¥39,200.00│
 * │   手续费 ¥5.00       │   手续费 ¥5.00     │
 * │   时间   2026-09-01  │   时间   2026-09-03│
 * │ - - - - - - - - - - - - - - - - - - - -  │
 * │  盈利            持有时间        单笔年化  │
 * │  +¥1,190.00      2天             5712.5% │
 * └──────────────────────────────────────────┘
 * ```
 *
 * [trade] 里的数字按 [displayCurrency] 解释：人民币是库里的原生口径（默认），
 * 港币口径由 [HkdDisplay] 先折好再传进来，卡片只负责带上对应的符号。
 */
@Composable
fun TradeCard(
    trade: Trade,
    now: Long,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    showActions: Boolean = true,
    displayCurrency: Currency = Currency.CNY
) {
    val hkd = displayCurrency == Currency.HKD
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {

            // ---------------------------------------------------- 顶部：档位标签 + 操作
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (trade.isClosed) {
                    StatusChip(text = "已T", color = ClosedBadgeColor)
                } else {
                    StatusChip(text = "未出T", color = OpenBadgeColor)
                }

                Spacer(modifier = Modifier.weight(1f))

                if (showActions) {
                    IconButton(onClick = onEditClick, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "修改记录",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDeleteClick, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除记录",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // ---------------------------------------------------- 中间：买入 │ 卖出
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.Top
            ) {
                SideColumn(
                    title = "买入",
                    titleColor = MaterialTheme.colorScheme.primary,
                    price = priceText(trade.buyPrice, displayCurrency),
                    amount = money(trade.buyAmount, displayCurrency),
                    fee = money(trade.buyFee, displayCurrency),
                    time = dateOnly(trade.buyTime),
                    modifier = Modifier.weight(1f)
                )

                // 中间那条分界线
                Box(
                    modifier = Modifier
                        .padding(horizontal = 10.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(DividerColor)
                )

                SideColumn(
                    title = "卖出",
                    titleColor = if (trade.isClosed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    price = if (trade.isClosed) priceText(trade.sellPrice ?: 0.0, displayCurrency) else "—",
                    amount = if (trade.isClosed) money(trade.sellAmount ?: 0.0, displayCurrency) else "—",
                    fee = if (trade.isClosed) money(trade.sellFee ?: 0.0, displayCurrency) else "—",
                    time = trade.sellTime?.takeIf { it.isNotBlank() }?.let { dateOnly(it) } ?: "尚未卖出",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ---------------------------------------------------- 虚线下方：盈利 / 持有时间 / 单笔年化
            DashedDivider()

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                BottomCell(
                    label = "盈利",
                    value = if (trade.isClosed) signedMoney(trade.profit, displayCurrency) else "—",
                    valueColor = if (trade.isClosed) profitColor(trade.profit) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.2f)
                )
                BottomCell(
                    label = "持有时间",
                    value = holdingText(trade.holdingDays(now)),
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                BottomCell(
                    label = "单笔年化",
                    value = percent(trade.annualRate(now)),
                    valueColor = profitColor(trade.annualRate(now) ?: 0.0),
                    modifier = Modifier.weight(1.1f)
                )
            }

            // 币种说明。人民币口径下保留「原按港币填」那两行，方便和交割单对；
            // 港币口径下主数字本身就是港币，改成说明这个数是按哪个汇率来的。
            if (hkd) {
                Spacer(modifier = Modifier.height(8.dp))
                val from = if (trade.isHkdInput) "原按港币填" else "按结算汇率折成港币"
                val rates = if (trade.isClosed) {
                    "　买 ${fxText(trade.buyFxRate)} · 卖 ${fxText(trade.sellFxRate)}"
                } else {
                    "　买 ${fxText(trade.buyFxRate)}"
                }
                Text(
                    text = "$from$rates（1 港币 = ? 元）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (trade.isHkdInput) {
                Spacer(modifier = Modifier.height(8.dp))
                val originalText = if (trade.isClosed) {
                    "原按港币填　买 ${priceText(trade.buyPriceOriginal ?: 0.0)}" +
                        " / 卖 ${priceText(trade.sellPriceOriginal ?: 0.0)} HK$"
                } else {
                    "原按港币填　买 ${priceText(trade.buyPriceOriginal ?: 0.0)} HK$"
                }
                Text(
                    text = originalText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val rateText = if (trade.isClosed) {
                    "结算汇率　买 ${fxText(trade.buyFxRate)} · 卖 ${fxText(trade.sellFxRate)}（1 港币 = ? 元）"
                } else {
                    "结算汇率　买 ${fxText(trade.buyFxRate)}（1 港币 = ? 元）"
                }
                Text(
                    text = rateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SideColumn(
    title: String,
    titleColor: Color,
    price: String,
    amount: String,
    fee: String,
    time: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = titleColor
        )
        Spacer(modifier = Modifier.height(4.dp))
        Field(label = "价格", value = price)
        Field(label = "金额", value = amount)
        Field(label = "手续费", value = fee)
        Field(label = "时间", value = time)
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BottomCell(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
