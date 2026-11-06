package com.tjiaoyi.ledger.data.model

/**
 * 把一条人民币口径的记录整体折成港币口径。纯函数，不查库、不碰 Context，
 * 汇率由调用方按买卖两侧分别给（[HkdDisplay] 负责查表），所以能单独跑数值校验。
 *
 * 约定：**1 [rate] = ? 元人民币**，所以港币数 = 人民币数 ÷ 汇率。
 *
 * 折完之后 [Trade.buyAmount] / [Trade.sellAmount] / 手续费 / 盈利全是港币，
 * 于是 [Trade.profit]、[Trade.buyCost]、[Trade.annualRate]、[Stats] 里那套
 * 「巅峰占用」逐日模拟都自动跟着变成港币口径，不用另写一套计算。
 *
 * 两个刻意的选择：
 * - **原币值优先**：当初按港币填过的记录（[Trade.buyPriceOriginal] 等），直接还原成
 *   填进去的那个数，不重新除一遍 —— 免得浮点误差让「38900.00」显示成「38899.99」。
 * - **盈利不折算，按港币重算**：卖出港币额 − 买入港币额 − 双边港币手续费。
 *   买卖两天汇率不同，人民币盈利里含着一段汇率损益；港币口径把那段剔掉，
 *   看到的是纯股价波动的盈亏，和行情软件里的盈亏一致。
 *
 * 未出T的记录卖出侧原样留着（本来就是 null），也不去查卖出日汇率。
 */
fun Trade.toHkdView(buyRate: Double, sellRate: Double): Trade {
    val buy = if (buyRate > 0.0) buyRate else 1.0

    val buyPrice = buyPriceOriginal.orPositive() ?: buyPrice / buy
    val buyAmount = buyAmountOriginal.orPositive() ?: buyAmount / buy

    val newSellPrice: Double?
    val newSellAmount: Double?
    val newSellFee: Double?
    val newSellRate: Double
    if (isClosed) {
        val sell = if (sellRate > 0.0) sellRate else 1.0
        newSellPrice = sellPriceOriginal.orPositive() ?: (sellPrice ?: 0.0) / sell
        newSellAmount = sellAmountOriginal.orPositive() ?: (sellAmount ?: 0.0) / sell
        newSellFee = (sellFee ?: 0.0) / sell
        newSellRate = sell
    } else {
        newSellPrice = sellPrice
        newSellAmount = sellAmount
        newSellFee = sellFee
        newSellRate = 0.0
    }

    return copy(
        buyPrice = buyPrice,
        buyAmount = buyAmount,
        buyFee = buyFee / buy,
        sellPrice = newSellPrice,
        sellAmount = newSellAmount,
        sellFee = newSellFee,
        // 实际用的汇率回填进去，卡片底部那行小字才说得出「这个港币数按哪个汇率折的」
        buyFxRate = buy,
        sellFxRate = newSellRate
    )
}

/** 原币值：null 和 0 都当成「没存」，避免把没填的字段折成 0.00。 */
private fun Double?.orPositive(): Double? = this?.takeIf { it > 0.0 }
