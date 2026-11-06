package com.tjiaoyi.ledger.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录入做T记录时用的币种。
 *
 * 数据库里买入价 / 买入金额 / 卖出价 / 卖出金额**一律存人民币**，
 * 这个字段只记「当初是按哪种币种填进去的」，配合 originalXxx 原币值用于回看对账。
 */
enum class Currency(val key: String, val label: String, val unit: String) {
    CNY("CNY", "人民币", "元"),
    HKD("HKD", "港币", "港元");

    companion object {
        fun fromKey(key: String?): Currency =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: CNY
    }
}

/** 一次成交的买卖侧，用来决定取哪一个结算汇兑比率。 */
enum class TradeSide { BUY, SELL }

/** 由录入页自动联网补入的汇率行标记，区别于用户手工录入。 */
const val FX_NOTE_EXCHANGE = "交易所更新"

/**
 * 一个港股通交易日的结算汇兑比率（中国结算日终公布，沪深两市同值）。
 *
 * **名字是反的，别改**：按交易所口径，
 * - [buyRate]  = 「买入结算汇兑比率」→ 投资者【卖出】港股、收到人民币时按它折算；
 * - [sellRate] = 「卖出结算汇兑比率」→ 投资者【买入】港股、付出人民币时按它折算。
 *
 * 人民币金额 = 港币金额 × 对应比率。
 */
data class FxRate(
    /** yyyyMMdd */
    val date: String = "",
    val buyRate: Double = 0.0,
    val sellRate: Double = 0.0,
    val note: String = "",
    /** true = 用户自己录入的覆盖值，优先于内置表 */
    val fromUser: Boolean = false
) {
    fun rateFor(side: TradeSide): Double = if (side == TradeSide.BUY) sellRate else buyRate
}

/** 按日期查表的结果。 */
data class FxLookup(
    val rate: Double,
    /** 实际采用的是哪一天的汇率，yyyyMMdd */
    val effectiveDate: String,
    /** false 表示当天不是交易日 / 表里没有，用了不晚于它的最近一个交易日 */
    val exact: Boolean,
    val fromUser: Boolean,
    /** 覆盖表里的备注，用来区分「交易所自动补入」和「用户手工录入」 */
    val note: String = ""
)

/** 把毫秒时间戳转成查表用的 yyyyMMdd。 */
fun dateKey(millis: Long): String =
    SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(millis))

/** "2026-11-02" / "2026-11-02 10:30" -> "20261102"；解析不了返回空串。 */
fun dateKeyFromText(text: String?): String {
    if (text.isNullOrBlank()) return ""
    val digits = text.filter { it.isDigit() }
    return if (digits.length >= 8) digits.take(8) else ""
}

/** yyyyMMdd -> yyyy-MM-dd，格式不对就原样返回。 */
fun prettyDateKey(key: String): String =
    if (key.length == 8 && key.all { it.isDigit() }) {
        "${key.substring(0, 4)}-${key.substring(4, 6)}-${key.substring(6, 8)}"
    } else key

/** "2026-11-02" -> "20261102"，已经是 yyyyMMdd 就原样返回。 */
fun normalizeDateKey(text: String): String {
    val digits = text.filter { it.isDigit() }
    return if (digits.length >= 8) digits.take(8) else ""
}

/** 汇率输入框统一保留 5 位小数，和交易所公布精度一致。 */
fun fxText(value: Double): String =
    String.format(Locale.US, "%.5f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }
