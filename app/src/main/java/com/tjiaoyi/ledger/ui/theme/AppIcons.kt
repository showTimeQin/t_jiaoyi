package com.tjiaoyi.ledger.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 手写矢量图标。
 *
 * 这里没有引入 androidx.compose.material:material-icons-extended，
 * 因为那个库体积 10MB+，而本项目关闭了代码混淆，为几个图标引入并不划算。
 * （material-icons-core 里已有的 Add / Delete / Edit / DateRange / Close 等直接用系统自带。）
 */

/** 统计图标：三根高低不同的柱子。 */
val BarChartIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "BarChart",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 13f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(7f)
            horizontalLineToRelative(-4f)
            close()

            moveTo(10f, 8f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(12f)
            horizontalLineToRelative(-4f)
            close()

            moveTo(16f, 3f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(17f)
            horizontalLineToRelative(-4f)
            close()
        }
    }.build()
}

/** 导入导出图标：一上一下两个反向箭头。 */
val DataTransferIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "DataTransfer",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 8f)
            lineTo(16f, 8f)
            lineTo(16f, 6f)
            lineTo(20f, 9f)
            lineTo(16f, 12f)
            lineTo(16f, 10f)
            lineTo(4f, 10f)
            close()

            moveTo(20f, 14f)
            lineTo(8f, 14f)
            lineTo(8f, 12f)
            lineTo(4f, 15f)
            lineTo(8f, 18f)
            lineTo(8f, 16f)
            lineTo(20f, 16f)
            close()
        }
    }.build()
}

/** 拖动把手：三条横杠。 */
val DragHandleIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "DragHandle",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 6f)
            horizontalLineToRelative(16f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-16f)
            close()

            moveTo(4f, 11f)
            horizontalLineToRelative(16f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-16f)
            close()

            moveTo(4f, 16f)
            horizontalLineToRelative(16f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-16f)
            close()
        }
    }.build()
}
