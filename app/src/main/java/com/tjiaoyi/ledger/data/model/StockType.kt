package com.tjiaoyi.ledger.data.model

/** 股票类型：决定手续费费率和「到手」目标收益率。 */
enum class StockType(val key: String, val label: String) {
    A_SHARE("A", "A股"),
    HK_CONNECT("HK", "港股通");

    companion object {
        fun fromKey(key: String?): StockType =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: A_SHARE
    }
}

/**
 * 按股票代码猜类型，用于老数据升级和用户还没手选时的默认值。
 *
 * - 5 位纯数字（00700 / 00941）→ 港股通
 * - 6 位纯数字（601318 / 002384 / 518600）→ A股
 * - 带 HK / hk 前缀 → 港股通
 * - 其它（空代码、字母代码）→ A股
 */
fun detectStockType(code: String): StockType {
    val raw = code.trim()
    if (raw.isEmpty()) return StockType.A_SHARE
    if (raw.startsWith("HK", ignoreCase = true)) return StockType.HK_CONNECT
    if (!raw.all { it.isDigit() }) return StockType.A_SHARE
    return if (raw.length <= 5) StockType.HK_CONNECT else StockType.A_SHARE
}
