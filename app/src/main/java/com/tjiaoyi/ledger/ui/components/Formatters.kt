package com.tjiaoyi.ledger.ui.components

import com.tjiaoyi.ledger.data.model.Currency
import com.tjiaoyi.ledger.data.model.formatDate
import com.tjiaoyi.ledger.data.model.parseDateOrNull
import java.util.Locale
import kotlin.math.abs

/**
 * 币种符号。详情页「按港币显示」时所有金额前缀跟着变，
 * 数字自己带币种，截屏发出去也不会被当成人民币。
 */
fun currencySymbol(currency: Currency): String = if (currency == Currency.HKD) "HK$" else "¥"

/** 金额：¥1,234.56；港币口径时是 HK$1,234.56 */
fun money(value: Double, currency: Currency = Currency.CNY): String =
    String.format(Locale.getDefault(), "${currencySymbol(currency)}%,.2f", value)

/** 带正负号的金额：+¥420.00 / -¥88.00 / ¥0.00 */
fun signedMoney(value: Double, currency: Currency = Currency.CNY): String {
    val sign = when {
        value > 0.0 -> "+"
        value < 0.0 -> "-"
        else -> ""
    }
    return sign + money(abs(value), currency)
}

/** 百分比：12.4%；null 显示「—」 */
fun percent(value: Double?, digits: Int = 1): String {
    if (value == null || !value.isFinite()) return "—"
    return String.format(Locale.getDefault(), "%.${digits}f%%", value)
}

/**
 * 价格：整数补两位小数，第三位小数非零才显示。380 → 380.00；380.5 → 380.50；380.123 → 380.123
 *
 * 人民币是 App 的默认口径，价格不带符号也不会读错；港币是非默认口径，带上前缀，
 * 这样「页面上凡是带 HK$ 的都是港币」一条规则就成立。
 */
fun priceText(value: Double, currency: Currency = Currency.CNY): String {
    val text = String.format(Locale.getDefault(), "%.3f", value)
    if (!text.contains('.')) return "$text.00"
    val intPart = text.substringBefore('.')
    val decimals = text.substringAfter('.').trimEnd('0')
    val bare = when (decimals.length) {
        0 -> "$intPart.00"
        1 -> "$intPart.$decimals" + "0"
        else -> "$intPart.$decimals"
    }
    return if (currency == Currency.HKD) "HK$$bare" else bare
}

/** 持有时间：3天 / 不足1天 / — */
fun holdingText(days: Int?): String {
    if (days == null || days < 0) return "—"
    if (days == 0) return "不足1天"
    return "${days}天"
}

/**
 * 日期一律只显示到天。
 *
 * 老备份里的买卖时间是 `yyyy-MM-dd HH:mm`，解析时时刻会被忽略，
 * 显示也得跟着截掉，不然同一张卡片会时而带时刻、时而不带。
 */
fun dateOnly(text: String?): String {
    if (text.isNullOrBlank()) return "—"
    val millis = parseDateOrNull(text) ?: return text
    return formatDate(millis)
}
