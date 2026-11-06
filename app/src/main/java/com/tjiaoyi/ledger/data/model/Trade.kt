package com.tjiaoyi.ledger.data.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 买入 / 卖出时间都只精确到「天」。 */
const val DATE_PATTERN = "yyyy-MM-dd"

/** 老版本的数据带时刻，读取时兼容；新写入的数据不再用这个格式。 */
const val LEGACY_TIME_PATTERN = "yyyy-MM-dd HH:mm"

const val STAMP_PATTERN = "yyyy-MM-dd HH:mm:ss"

const val MILLIS_PER_DAY = 86_400_000L

/**
 * 宽松解析日期文本，兼容老数据里的「yyyy-MM-dd HH:mm」。
 *
 * 解析不出来返回 null，避免脏数据把页面搞崩。
 */
fun parseDateOrNull(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    for (pattern in listOf(LEGACY_TIME_PATTERN, DATE_PATTERN)) {
        try {
            val format = SimpleDateFormat(pattern, Locale.getDefault())
            format.isLenient = false
            return format.parse(text)?.time
        } catch (_: Exception) {
            // 换下一种格式再试
        }
    }
    return null
}

fun formatDate(millis: Long): String =
    SimpleDateFormat(DATE_PATTERN, Locale.getDefault()).format(Date(millis))

fun nowStamp(): String =
    SimpleDateFormat(STAMP_PATTERN, Locale.getDefault()).format(Date())

/** 某个时刻所在那天的 0 点。 */
fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * 一条做T记录：一次买入 + 一次卖出。
 *
 * 买卖时间都只到「天」，不记时刻。
 * 卖出侧留空表示「未出T」（只买了还没卖）。
 * 盈利与年利率都只在卖出侧填了之后才有意义。
 *
 * 港股通可以按港币录入：录入时按当日结算汇兑比率折成人民币再存进上面的字段，
 * 原币数值与所用汇率另外留一份，方便日后和交割单对账。A股 / 按人民币录入的记录
 * 这些字段保持默认值，老数据升级上来也是默认值。
 */
data class Trade(
    val id: Long = 0,
    val stockId: Long,
    val buyPrice: Double = 0.0,
    val buyAmount: Double = 0.0,
    val buyFee: Double = 0.0,
    /** 买入日期，yyyy-MM-dd */
    val buyTime: String = "",
    val sellPrice: Double? = null,
    val sellAmount: Double? = null,
    val sellFee: Double? = null,
    /** 卖出日期，yyyy-MM-dd；null 表示没卖 */
    val sellTime: String? = null,
    val createdAt: String = "",

    // ------------------------------------------------------------ 港币录入留痕
    /** 录入时选的币种；库里的价 / 金额始终是人民币 */
    val inputCurrency: Currency = Currency.CNY,
    /** 买入侧用的换算汇率（1 港币 = ? 元人民币），按人民币录入时为 0 */
    val buyFxRate: Double = 0.0,
    /** 卖出侧用的换算汇率，与买入侧可以不同（跨日做T） */
    val sellFxRate: Double = 0.0,
    val buyPriceOriginal: Double? = null,
    val buyAmountOriginal: Double? = null,
    val sellPriceOriginal: Double? = null,
    val sellAmountOriginal: Double? = null
) {
    /** 卖出时间填了就算「已T」，否则算「未出T」 */
    val isClosed: Boolean get() = !sellTime.isNullOrBlank()

    /** 这条记录当初是不是按港币填的 */
    val isHkdInput: Boolean get() = inputCurrency == Currency.HKD

    /** 这笔实际压进去的买入成本 = 买入金额 + 买入手续费 */
    val buyCost: Double get() = buyAmount + buyFee

    /** 盈利 = 卖出金额 − 买入金额 − 买入手续费 − 卖出手续费（未卖出记 0） */
    val profit: Double
        get() = if (isClosed) {
            (sellAmount ?: 0.0) - buyAmount - buyFee - (sellFee ?: 0.0)
        } else {
            0.0
        }

    /** 是否做T成功（盈利为正） */
    val isWin: Boolean get() = isClosed && profit > 0.0

    /**
     * 持有天数。买卖两端各截到当天 0 点后数整天数，所以和填几点几分无关。
     *
     * - 已T：卖出日期 − 买入日期，**不足 1 天按 1 天计**
     *   （当天买当天卖就是这种情况，兜住年利率的分母）
     * - 未出T：买入日期 至 今天，最少 0（显示成「不足1天」）
     *
     * 日期读不出来时返回 null，显示成「—」。
     */
    fun holdingDays(now: Long = System.currentTimeMillis()): Int? {
        val start = startOfDay(parseDateOrNull(buyTime) ?: return null)
        val end = if (isClosed) {
            startOfDay(parseDateOrNull(sellTime) ?: return null)
        } else {
            startOfDay(now)
        }
        val days = ((end - start) / MILLIS_PER_DAY).toInt()
        if (days <= 0) return if (isClosed) 1 else 0
        return days
    }

    /**
     * 年利率（%）= (盈利 ÷ 买入成本) ÷ (持有天数 ÷ 365) × 100
     *
     * 分母用 [buyCost]（买入金额 + 买入手续费）而不是光用买入金额，和首页那个
     * 股票级年利率的分母（巅峰占用资金）保持同一口径：分子 [profit] 已经扣掉双边
     * 手续费，分母这边也把买入时实际掏出去的手续费算成占用，不能只让分子认账。
     *
     * 只对已T的记录有意义；买入成本为 0 时返回 null。
     * 持有天数最少按 1 天算，所以当天买卖也一定算得出来、不会除以 0。
     */
    fun annualRate(now: Long = System.currentTimeMillis()): Double? {
        if (!isClosed || buyCost <= 0.0) return null
        val days = holdingDays(now) ?: return null
        if (days <= 0) return null
        return (profit / buyCost) / (days / 365.0) * 100.0
    }
}

/**
 * 详情页「已T」列表的展示顺序：
 * 1. 卖出日期从近到远（老数据可能带时刻，先归一到当天 0 点，同一天才算同一档）；
 * 2. **同一天按卖出价从高到低**；
 * 3. 仍相同则按买入日期从近到远、id 倒序兜底，保证顺序稳定。
 *
 * 卖出价取库里的人民币口径（港币录入的记录落库时已折算），与卡片主显示的价格一致。
 * 单只股票详情与「总账户」合并视图共用这一个比较器，避免两处规则走偏。
 */
val CLOSED_TRADE_ORDER: Comparator<Trade> =
    compareByDescending<Trade> {
        parseDateOrNull(it.sellTime)?.let { millis -> startOfDay(millis) } ?: 0L
    }
        .thenByDescending { it.sellPrice ?: 0.0 }
        .thenByDescending { parseDateOrNull(it.buyTime) ?: 0L }
        .thenByDescending { it.id }
