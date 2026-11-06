package com.tjiaoyi.ledger.ui.components

import android.content.Context
import com.tjiaoyi.ledger.data.db.DatabaseHelper
import com.tjiaoyi.ledger.data.model.AppState
import com.tjiaoyi.ledger.data.model.FxRateRepository
import com.tjiaoyi.ledger.data.model.Trade
import com.tjiaoyi.ledger.data.model.TradeSide
import com.tjiaoyi.ledger.data.model.dateKeyFromText
import com.tjiaoyi.ledger.data.model.toHkdView

/**
 * 详情页「按港币显示」的汇率取用器：只负责查出买卖两侧各自的结算汇兑比率，
 * 具体怎么折交给纯函数 [toHkdView]（那份逻辑可以脱离 Android 单独校验）。
 *
 * 汇率（1 港币 = ? 元人民币）的取用优先级：
 * 1. **当初按港币录入的记录** —— 用落库时存的 [Trade.buyFxRate] / [Trade.sellFxRate]，
 *    和录入页那对折算汇率是同一个值，折回来的港币与当初填进去的一致。
 * 2. **按人民币录入的记录** —— 按「这一侧的成交日期」查 [FxRateRepository]。
 *    交易所口径名字是反的：买入港股按「卖出结算汇兑比率」付出人民币，卖出港股按
 *    「买入结算汇兑比率」收到人民币，所以买卖两侧各查各的，和录入页同一套规则。
 * 3. 表里查不到（日期早于内置表 / assets 读失败）—— 回退到费率设置里的兜底汇率。
 */
class HkdDisplay(private val context: Context, private val db: DatabaseHelper) {

    /** 整条记录折成港币口径；只读换算，不写库。 */
    fun convert(trade: Trade): Trade = trade.toHkdView(
        buyRate = rateOf(trade.buyTime, trade.buyFxRate, TradeSide.BUY),
        sellRate = if (trade.isClosed) rateOf(trade.sellTime, trade.sellFxRate, TradeSide.SELL) else 0.0
    )

    /**
     * 1 港币 = ? 元人民币。任何分支都返回正数，不会出现除以 0。
     *
     * 兜底值本身也被改成 0 时按 1:1 显示（[toHkdView] 里同样有这层保护），
     * 宁可数字不准也不让页面崩。
     */
    private fun rateOf(dateText: String?, storedRate: Double, side: TradeSide): Double {
        if (storedRate > 0.0) return storedRate
        val key = dateKeyFromText(dateText)
        if (key.length == 8) {
            val hit = FxRateRepository.lookupDate(context, db, key, side)
            if (hit != null && hit.rate > 0.0) return hit.rate
        }
        val fallback = AppState.feeConfig.hkFxRate
        return if (fallback > 0.0) fallback else 1.0
    }
}
