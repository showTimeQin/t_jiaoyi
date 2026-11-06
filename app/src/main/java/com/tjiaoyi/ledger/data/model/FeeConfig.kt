package com.tjiaoyi.ledger.data.model

/**
 * 手续费费率与目标收益率。所有 rate 都是「占成交金额的比例」，例如 0.0005 = 0.05% = 万5。
 *
 * A股默认值来自东方财富四张交割单逐笔反推（2026-10/11，沪市 601318、深市 002384 各一笔买一笔卖）：
 * - 全佣（交割单里的「佣金」+「其他费用」）= max(5元, 成交额 × 万2.355)，买卖双向各收一次
 *   37788 × 0.0002355 = 8.90 ✓　34601 × 0.0002355 = 8.15 ✓
 * - 沪市过户费 = 成交额 × 万0.1，双向，单列一行；深市过户费为 0（已并进「其他费用」）
 *   10228 → 0.10 ✓　10898 → 0.11 ✓　002384 两笔过户费都是 0.000 ✓
 * - 印花税 = 成交额 × 万5，仅卖出　10898 → 5.45 ✓
 * - 最低 5 元触发时：4.46 + 0.54 = 5.00（买）、4.41 + 0.59 = 5.00（卖），买卖各一次
 *
 * 港股通默认值 = 东方财富腾讯控股 20 笔持仓明细逐笔反推（2026-06/07 七笔、10 月七笔、11 月六笔）：
 * - 佣金 = 成交额 × 万1.5，双边。**没有 5 元最低佣金**：成交额 3.2~3.3 万的三笔按万1.5 算是
 *   4.84 / 4.95 / 4.98 元，与实收一致；若套 5 元下限会各多收 0.03~0.14 元。真实下限只能确定
 *   低于 4.84 元，[hkMinCommission] 填 1 元纯粹是兜底，这 20 笔分辨不出来
 * - 印花税 = 港币成交额 × 0.1%，双边，向上取整到整港币后按当日结算汇率折回人民币
 * - 交易征费 0.0027% + 财汇局征费 0.00015% + 交易费 0.00565% + 股份交收费 0.0042%，双边
 * - 证券组合费按日终持仓市值逐日计提，多数券商暂未向客户收取，这里不计入
 *
 * 上面这套口径逐笔最大偏差 0.02 元、20 笔累计 0.17 元。之前的万2.5 + 最低 5 元是照券商公示标准
 * 填的，对不上实收，每笔会多算 3.2~4.0 元（1.5 时代那句「7 笔交割单复核」并没有真的对上）。
 *
 * 港股通是「港币报价、人民币结算」。做T记录可以按港币或人民币录入，
 * **库里的价格与金额始终是人民币**，按比例收的费用直接用人民币成交额算即可。
 *
 * 1.6.1 起，印花税「取整到整港币」这一步改用该笔买卖日期的**真实结算汇兑比率**
 * （由 FxRateRepository 逐日查表，按港币录入时人民币成交额 ÷ 汇率恰好就是原始港币成交额），
 * 不再用 1.5 时代为了凑 7 笔交割单跳档反推出来的固定拟合值 0.9435。
 * [hkFxRate] 现在只作为「内置表 + 自定义表都查不到任何汇率」时的离线兜底，
 * 默认取内置表最后一个交易日的买入侧汇率。
 */
data class FeeConfig(
    // ---------------------------------------------------------------- A股
    /** 全佣（含经手费、证管费及深市过户费）费率，0.0002355 = 万2.355 */
    val aCommissionRate: Double = 0.0002355,
    /** 单笔最低佣金，买卖双向各收一次；「不免5」就是它 */
    val aMinCommission: Double = 5.0,
    /** 沪市过户费，双向；深市不另收（已含在全佣里），按股票代码 6 开头判断 */
    val aTransferRate: Double = 0.00001,
    /** 印花税，仅卖出 */
    val aStampRate: Double = 0.0005,

    // ---------------------------------------------------------------- 港股通
    /** 佣金费率，0.00015 = 万1.5（20 笔东财港股通持仓明细逐笔反推，见类注释） */
    val hkCommissionRate: Double = 0.00015,
    /** 单笔最低佣金（人民币），双边；实测没有 5 元下限，这里只留 1 元兜底 */
    val hkMinCommission: Double = 1.0,
    /** 印花税，双边，0.001 = 0.1% */
    val hkStampRate: Double = 0.001,
    /** 交易征费 0.0027% */
    val hkLevyRate: Double = 0.000027,
    /** 财汇局交易征费 0.00015% */
    val hkFrcRate: Double = 0.0000015,
    /** 联交所交易费 0.00565% */
    val hkTradingRate: Double = 0.0000565,
    /** 股份交收费 0.0042% */
    val hkSettlementRate: Double = 0.000042,
    /** 离线兜底汇率（1 港币 = ? 元人民币）；正常情况一律按买卖日期查 FxRateRepository */
    val hkFxRate: Double = 0.85086,
    /** 印花税是否按港币成交额向上取整到整港币（不足 1 港币按 1 港币） */
    val hkStampRoundUpToHkd: Boolean = true,

    // ---------------------------------------------------------------- 目标到手收益率
    /** A股目标到手收益率：净盈利 ÷ 买入成本 */
    val aTargetRate: Double = 0.04,
    /** 港股通目标到手收益率 */
    val hkTargetRate: Double = 0.03
) {
    fun targetRate(type: StockType): Double =
        if (type == StockType.HK_CONNECT) hkTargetRate else aTargetRate

    fun targetLabel(type: StockType): String =
        String.format(java.util.Locale.US, "%.1f%%", targetRate(type) * 100)

    /** 转成「万分之 X」便于设置页显示与编辑 */
    fun toWan(rate: Double): String = trimNumber(rate * 10_000)

    companion object {
        fun fromWan(text: String, fallback: Double): Double {
            val v = text.trim().toDoubleOrNull() ?: return fallback
            return if (v < 0) fallback else v / 10_000.0
        }

        /** 固定用 US locale，避免小数点是逗号的语言环境下把数字写坏。 */
        fun trimNumber(value: Double): String =
            String.format(java.util.Locale.US, "%.6f", value)
                .trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }
}
