package com.tjiaoyi.ledger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tjiaoyi.ledger.ui.theme.DashedDividerColor

/**
 * 虚线分隔线。
 *
 * material3 的 Divider 只画实线，这里用 Canvas + dashPathEffect 手绘一条，
 * 用于记录卡片里「买入卖出区」与「盈利区」之间的那条虚线。
 */
@Composable
fun DashedDivider(
    modifier: Modifier = Modifier,
    color: Color = DashedDividerColor,
    strokeWidth: Dp = 1.dp,
    dashLength: Dp = 5.dp,
    gapLength: Dp = 4.dp
) {
    val density = LocalDensity.current
    val strokePx = with(density) { strokeWidth.toPx() }
    val dashPx = with(density) { dashLength.toPx() }
    val gapPx = with(density) { gapLength.toPx() }

    Canvas(modifier = modifier.fillMaxWidth().height(strokeWidth)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = strokePx,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx), 0f)
        )
    }
}
