package com.tjiaoyi.ledger.data.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.round

/**
 * 手续费计算 + 建议卖出价反解。纯 Kotlin，不依赖 Android，方便单独校验。
 *
 * 口径说明：
 * - 「不免5」= 佣金按 max(最低佣金, 成交额 × 费率) 收，买入、卖出**各算一次**。
 * - A股：全佣 = 交割单的「佣金」+「其他费用」（含经手费、证管费及深市过户费）；
 *   买入 = 全佣 + 沪市过户费；卖出 = 全佣 + 沪市过户费 + 印花税。
 * - 沪市（代码 6 开头）过户费单列，深市（0/3 开头）过户费为 0，已并进全佣，
 *   否则深市会被重复计一次过户费。代码没填时按沪市算，宁可多算一点也别低估到手收益。
 * - 港股通：买卖双边都收佣金、印花税和四项规费，没有过户费单列。
 *
 * 港股通印花税按**港币成交额**的 0.1% 计、向上取整到整港币，再按当日结算汇兑比率
 * 折回人民币。1.6.1 起这个汇率由调用方按买卖日期从 FxRateRepository 查表传入
 * （[fxRate] 参数）：按港币录入时「人民币成交额 ÷ 汇率」正好还原出原始港币成交额，
 * 按人民币录入时则用当日真实汇率反推港币成交额。查不到任何日期数据时才回退到
 * [FeeConfig.hkFxRate] 这个离线兜底值。
 */
object FeeCalculator {

    /** 保留两位，和交割单的金额精度一致。 */
    private fun round2(value: Double): Double = round(value * 100.0) / 100.0

    /** 单笔佣金：不低于最低佣金，买卖双向各调用一次。 */
    private fun commission(amount: Double, rate: Double, minimum: Double): Double {
        if (amount <= 0.0) return 0.0
        return max(minimum, round2(amount * rate))
    }

    /**
     * 沪市 A股才单列过户费。600/601/603/605/688 都是 6 开头的六位代码；
     * 认不出来（空代码、字母代码）时按沪市处理，避免把手续费算少。
     */
    private fun shanghaiTransfer(amount: Double, code: String, config: FeeConfig): Double {
        val c = code.trim()
        val isDefinitelyShenzhen = c.length == 6 && c.all { it.isDigit() } &&
            (c.startsWith("0") || c.startsWith("3"))
        return if (isDefinitelyShenzhen) 0.0 else amount * config.aTransferRate
    }

    /**
     * 买入手续费。
     *
     * @param fxRate 买入日期的真实结算汇兑比率（1 港币 = ? 元人民币），只对港股通有效；
     *               <= 0 时回退到 [FeeConfig.hkFxRate]
     */
    fun buyFee(
        type: StockType,
        amount: Double,
        config: FeeConfig,
        stockCode: String = "",
        fxRate: Double = 0.0
    ): Double {
        if (amount <= 0.0) return 0.0
        return round2(
            when (type) {
                StockType.A_SHARE ->
                    commission(amount, config.aCommissionRate, config.aMinCommission) +
                        shanghaiTransfer(amount, stockCode, config)

                StockType.HK_CONNECT ->
                    commission(amount, config.hkCommissionRate, config.hkMinCommission) +
                        amount * (config.hkLevyRate + config.hkFrcRate +
                            config.hkTradingRate + config.hkSettlementRate) +
                        hkStampDuty(amount, config, fxRate)
            }
        )
    }

    /** 卖出手续费；[fxRate] 是卖出日期的真实结算汇兑比率。 */
    fun sellFee(
        type: StockType,
        amount: Double,
        config: FeeConfig,
        stockCode: String = "",
        fxRate: Double = 0.0
    ): Double {
        if (amount <= 0.0) return 0.0
        return round2(
            when (type) {
                StockType.A_SHARE ->
                    commission(amount, config.aCommissionRate, config.aMinCommission) +
                        shanghaiTransfer(amount, stockCode, config) +
                        amount * config.aStampRate

                StockType.HK_CONNECT ->
                    commission(amount, config.hkCommissionRate, config.hkMinCommission) +
                        amount * (config.hkLevyRate + config.hkFrcRate +
                            config.hkTradingRate + config.hkSettlementRate) +
                        hkStampDuty(amount, config, fxRate)
            }
        )
    }

    /**
     * 港股通印花税：按港币成交额的 0.1% 计，取整到整港币，不足 1 港币按 1 港币，
     * 再按结算汇率折回人民币。
     *
     * 库里的成交额是人民币，除以当日结算汇率即港币成交额；按港币录入的记录
     * 正是用同一个汇率折成人民币的，所以这里能精确还原原始港币成交额。
     */
    private fun hkStampDuty(amountInCny: Double, config: FeeConfig, fxRate: Double): Double {
        val fx = (if (fxRate > 0.0) fxRate else config.hkFxRate).coerceAtLeast(0.0001)
        val hkdTurnover = amountInCny / fx
        // 费率在 SharedPreferences 里按 Float 存，0.001 读回来是 0.0010000000475。
        // 港币成交额正好落在整港币边界（例如 62000 港元 → 62.0000029）时，
        // 这点误差会把 ceil 推到下一档，白多收 1 港币。用相对容差把浮点噪声吃掉：
        // 真实的成交额不会精确到 0.0001 港币以内，1e-6 的量级完全安全。
        val raw = hkdTurnover * config.hkStampRate
        val stampHkd = if (config.hkStampRoundUpToHkd) {
            max(1.0, ceil(raw * (1.0 - 1e-6) - 1e-9))
        } else {
            raw
        }
        return stampHkd * fx
    }

    /**
     * 反解「建议卖出价」与「预计盈利」。
     *
     * 目标：净盈利 = 买入成本 × 目标到手收益率（A股 4%、港股通 3%），
     * 其中买入成本 = 买入金额 + 买入手续费（用户手改过就用改后的值）。
     *
     * 设卖出金额为 S、卖出侧手续费为 f(S)，要求
     *     S − 买入金额 − 买入手续费 − f(S) = 买入成本 × 目标收益率
     * 即 S = 买入成本 × (1 + 目标收益率) + f(S)。
     * f(S) 单调递增且斜率远小于 1，用不动点迭代即可稳定收敛；
     * 这样「最低佣金 5 元」这种分段函数也能被正确算进去（也就是「不免5」）。
     *
     * 反解出的价格保留 3 位小数，再用这个价格回算一次卖出金额与手续费，
     * 保证页面显示的「建议卖出价」和「预计盈利」是自洽的。
     *
     * @param sellFxRate 卖出日期的真实结算汇兑比率，用于反解过程中的卖出侧印花税取整
     */
    fun suggest(
        type: StockType,
        buyPrice: Double,
        buyAmount: Double,
        buyFee: Double,
        config: FeeConfig,
        stockCode: String = "",
        sellFxRate: Double = 0.0
    ): Suggestion? {
        if (buyPrice <= 0.0 || buyAmount <= 0.0) return null
        val shares = buyAmount / buyPrice
        if (shares <= 0.0) return null

        val buyCost = buyAmount + buyFee
        val targetRate = config.targetRate(type)
        val targetProfit = buyCost * targetRate
        val requiredNet = buyCost + targetProfit

        // 不动点迭代：S = requiredNet + sellFee(S)
        var sellAmount = requiredNet
        repeat(80) {
            val next = requiredNet + sellFee(type, sellAmount, config, stockCode, sellFxRate)
            if (abs(next - sellAmount) < 1e-9) return@repeat
            sellAmount = next
        }

        // 价格保留 3 位小数（港股常有三位小数），再用它回算真实盈利
        val suggestedPrice = round(sellAmount / shares * 1000.0) / 1000.0
        val finalSellAmount = suggestedPrice * shares
        val finalSellFee = sellFee(type, finalSellAmount, config, stockCode, sellFxRate)
        val profit = finalSellAmount - buyAmount - buyFee - finalSellFee

        return Suggestion(
            suggestedPrice = suggestedPrice,
            sellAmount = finalSellAmount,
            sellFee = finalSellFee,
            profit = profit,
            targetRate = targetRate,
            targetProfit = targetProfit,
            shares = shares
        )
    }
}

/** 反解结果，全部是人民币口径。 */
data class Suggestion(
    val suggestedPrice: Double,
    val sellAmount: Double,
    val sellFee: Double,
    val profit: Double,
    val targetRate: Double,
    val targetProfit: Double,
    val shares: Double
)
