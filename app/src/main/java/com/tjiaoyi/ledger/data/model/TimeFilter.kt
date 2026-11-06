package com.tjiaoyi.ledger.data.model

import java.util.Calendar

/** 统计区间：按自然日的闭区间，[startMillis 00:00:00.000, endMillis 23:59:59.999]。 */
data class DateRange(val startMillis: Long, val endMillis: Long) {
    operator fun contains(millis: Long): Boolean = millis in startMillis..endMillis
}

/** 某天的 23:59:59.999 */
fun endOfDay(millis: Long): Long = startOfDay(millis) + MILLIS_PER_DAY - 1

/** 取年份，如 2026 */
fun yearOf(millis: Long): Int = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)

/** 取月份，1~12 */
fun monthOf(millis: Long): Int =
    Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.MONTH) + 1

/**
 * 统计页的时间筛选。
 *
 * 「近一年」这一档同时也是首页卡片和年利率的基准窗口。
 */
sealed interface TimeFilter {

    /** 最近 N 个月（含今天）。近一年 = [LastMonths]`(12)` */
    data class LastMonths(val months: Int) : TimeFilter

    /** 某个自然年，如 2026 全年 */
    data class Year(val year: Int) : TimeFilter

    /** 某个自然月，如 2026 年 8 月 */
    data class Month(val year: Int, val month: Int) : TimeFilter

    /** 不限时间：从最早一笔买入到今天 */
    object All : TimeFilter

    companion object {
        val LastYear = LastMonths(12)
        val LastHalfYear = LastMonths(6)
        val LastQuarter = LastMonths(3)

        /** 默认筛选：近一年 */
        val Default: TimeFilter = LastYear
    }
}

/** 筛选的中文名，如「近一年」「2026年8月」 */
val TimeFilter.label: String
    get() = when (this) {
        is TimeFilter.LastMonths -> when (months) {
            12 -> "近一年"
            6 -> "近半年"
            3 -> "近三月"
            else -> "近${months}个月"
        }
        is TimeFilter.Year -> "${year}年"
        is TimeFilter.Month -> "${year}年${month}月"
        TimeFilter.All -> "全部"
    }

/**
 * 收益率这一格的标题。
 *
 * 只有窗口正好是一整年时才叫「年利率」，其余区间老实叫「收益率」——
 * 一年以内的收益率没有年化，直接叫年利率会误导。
 */
val TimeFilter.rateLabel: String
    get() = when (this) {
        is TimeFilter.LastMonths -> when (months) {
            12 -> "年利率"
            6 -> "近半年收益率"
            3 -> "近三月收益率"
            else -> "收益率"
        }
        is TimeFilter.Year -> "年度收益率"
        is TimeFilter.Month -> "月度收益率"
        TimeFilter.All -> "累计收益率"
    }

/**
 * 把筛选换算成具体区间。
 *
 * [earliest] 是最早一笔记录的买入日期，只在 [TimeFilter.All] 时用得上。
 */
fun TimeFilter.resolve(now: Long, earliest: Long? = null): DateRange = when (this) {
    is TimeFilter.LastMonths -> {
        val start = Calendar.getInstance().apply {
            timeInMillis = startOfDay(now)
            add(Calendar.MONTH, -months)
        }
        DateRange(start.timeInMillis, endOfDay(now))
    }

    is TimeFilter.Year -> {
        val start = Calendar.getInstance().apply {
            clear()
            set(year, Calendar.JANUARY, 1)
        }
        val next = Calendar.getInstance().apply {
            clear()
            set(year + 1, Calendar.JANUARY, 1)
        }
        DateRange(start.timeInMillis, next.timeInMillis - 1L)
    }

    is TimeFilter.Month -> {
        val start = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, 1)
        }
        val next = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, 1)
            add(Calendar.MONTH, 1)
        }
        DateRange(start.timeInMillis, next.timeInMillis - 1L)
    }

    TimeFilter.All -> DateRange(
        startMillis = startOfDay(earliest ?: now),
        endMillis = endOfDay(now)
    )
}
