package com.tjiaoyi.ledger.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tjiaoyi.ledger.ui.theme.ClosedBadgeColor
import com.tjiaoyi.ledger.ui.theme.OpenBadgeColor

/** 胶囊标签的统一外观：淡色底 + 同色描边 + 同色文字。 */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** 未出T 标签 */
@Composable
fun OpenBadge(count: Int, modifier: Modifier = Modifier) {
    StatusChip(text = "未出T $count", color = OpenBadgeColor, modifier = modifier)
}

/** 已T 标签 */
@Composable
fun ClosedBadge(count: Int, modifier: Modifier = Modifier) {
    StatusChip(text = "已T $count", color = ClosedBadgeColor, modifier = modifier)
}
