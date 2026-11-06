package com.tjiaoyi.ledger.data.model

/**
 * 一只股票的汇总指标。首页卡片和详情页头部用它。
 *
 * 分两套口径：
 * - **全部**：[profit]、[closedCount]、[winCount]、[winRate] 统计全部已T记录
 * - **近一年**：[yearProfit]、[peakOccupied]、[annualRate] 只看最近 365 天
 *
 * [occupied] 是「当前占用资金」，未出T记录的买入金额 + 买入手续费，本身就是当下状态。
 */
data class StockStat(
    val stockId: Long = 0L,
    val closedCount: Int = 0,
    val openCount: Int = 0,
    val winCount: Int = 0,
    val profit: Double = 0.0,
    val occupied: Double = 0.0,
    val winRate: Double? = null,
    /** 近一年已T记录的盈利合计 */
    val yearProfit: Double = 0.0,
    /** 近一年巅峰占用资金 */
    val peakOccupied: Double = 0.0,
    /** 年利率 = 近一年已T盈利 ÷ 近一年巅峰占用资金 × 100；巅峰占用为 0 时为 null */
    val annualRate: Double? = null
) {
    val totalCount: Int get() = closedCount + openCount
}

/**
 * 某个时间区间内的统计结果，统计页用。
 *
 * [occupied] 和 [openCount] 是「当前」状态（未出T的持仓），不随区间变化；
 * 其余指标都只看区间内。
 */
data class WindowStat(
    val profit: Double = 0.0,
    val closedCount: Int = 0,
    val winCount: Int = 0,
    val openCount: Int = 0,
    val occupied: Double = 0.0,
    val peakOccupied: Double = 0.0
) {
    /** 区间收益率 = 区间内已T盈利 ÷ 区间巅峰占用资金 */
    val rate: Double? get() = if (peakOccupied > 0.0) profit / peakOccupied * 100.0 else null

    /** 区间胜率 = 区间内已T中盈利笔数 ÷ 区间内已T笔数 */
    val winRate: Double? get() = if (closedCount > 0) winCount * 100.0 / closedCount else null
}

object Stats {

    /** 首页卡片 / 详情页头部：全部口径 + 近一年口径一起算出来。 */
    fun ofStock(
        stockId: Long,
        trades: List<Trade>,
        now: Long = System.currentTimeMillis()
    ): StockStat {
        val closed = trades.filter { it.isClosed }
        val open = trades.filter { !it.isClosed }
        val winCount = closed.count { it.isWin }
        val year = window(trades, TimeFilter.LastYear.resolve(now), now)

        return StockStat(
            stockId = stockId,
            closedCount = closed.size,
            openCount = open.size,
            winCount = winCount,
            profit = closed.sumOf { it.profit },
            occupied = open.sumOf { it.buyCost },
            winRate = if (closed.isEmpty()) null else winCount * 100.0 / closed.size,
            yearProfit = year.profit,
            peakOccupied = year.peakOccupied,
            annualRate = year.rate
        )
    }

    /**
     * 统计页：按时间区间统计一批记录。
     *
     * 盈利与胜率只看**卖出日期落在区间内**的已T记录；巅峰占用资金在区间上逐日模拟。
     * 传进来的 [trades] 可以是多只股票的全部记录，这样算出来的是组合级的真实峰值
     * （同一天各股占用相加再取最大），比「各股峰值再相加」更准。
     */
    fun window(
        trades: List<Trade>,
        range: DateRange,
        now: Long = System.currentTimeMillis()
    ): WindowStat {
        val closed = trades.filter { trade ->
            val sell = parseDateOrNull(trade.sellTime) ?: return@filter false
            sell in range
        }
        val open = trades.filter { !it.isClosed }

        return WindowStat(
            profit = closed.sumOf { it.profit },
            closedCount = closed.size,
            winCount = closed.count { it.isWin },
            openCount = open.size,
            occupied = open.sumOf { it.buyCost },
            peakOccupied = peakOccupied(trades, range, now)
        )
    }

    /**
     * 区间内巅峰占用资金。
     *
     * 做法是逐日模拟：每一天算一次「当天还没卖出的买入成本合计」，取最大值。
     *
     * 占用区间取**买入日到卖出日（含两端）**。含卖出日这一步很关键 —— 做T大量是
     * 「当天买当天卖」，如果卖出当天就不再算占用，日内T的占用会变成 0，年利率的分母就没了。
     * 未卖出的记录占用到今天为止；买入早于区间起点的跨期持仓照样计入（它确实占着钱）。
     * 未来日期不算（区间是当年的 12 月，今天才 9 月时只模拟到今天）。
     *
     * 等价说法：把每笔的买入当 +成本、卖出当 −成本，2N 个事件按日期排序后扫一遍取最大值。
     * 这里没排序，改成按天做差分数组（`delta[买入日] += 成本`、`delta[卖出日 + 1] -= 成本`）
     * 再顺扫一遍。结果完全一致（随机数据 500 组逐一对过），但省掉了排序：O(N + 区间天数)，
     * 区间天数最多 366，而且不分配 2N 个事件对象。
     *
     * 注意事件扫描那种写法有个坑：卖出要减**买入成本**，不能减卖出金额。减错的话盈亏会混进
     * 「占用资金」，赚钱那笔留下的负余额会把后面的持仓抵消掉，峰值被低估。
     */
    fun peakOccupied(trades: List<Trade>, range: DateRange, now: Long): Double {
        val from = startOfDay(range.startMillis)
        val to = startOfDay(minOf(range.endMillis, now))
        if (to < from) return 0.0

        val days = ((to - from) / MILLIS_PER_DAY).toInt() + 1
        val delta = DoubleArray(days + 1)

        for (trade in trades) {
            val buyDay = startOfDay(parseDateOrNull(trade.buyTime) ?: continue)
            val sellDay = if (trade.isClosed) {
                startOfDay(parseDateOrNull(trade.sellTime) ?: continue)
            } else {
                to
            }

            val a = ((maxOf(buyDay, from) - from) / MILLIS_PER_DAY).toInt()
            val b = ((minOf(sellDay, to) - from) / MILLIS_PER_DAY).toInt()
            // 整段都在区间之外，或买入日在区间之后，都不参与
            if (a > days || b < 0 || b < a) continue

            val lo = a.coerceIn(0, days)
            val hi = b.coerceIn(0, days - 1)
            delta[lo] += trade.buyCost
            delta[hi + 1] -= trade.buyCost
        }

        var current = 0.0
        var peak = 0.0
        for (i in 0 until days) {
            current += delta[i]
            if (current > peak) peak = current
        }
        return peak
    }
}
