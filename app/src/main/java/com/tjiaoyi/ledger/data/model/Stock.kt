package com.tjiaoyi.ledger.data.model

/**
 * 一只股票。名称和代码都是创建时手输的。
 *
 * [accountId] 归属的分账户；总账户不是一行数据，是查询时把各账户按代码合并出来的视图。
 * [stockType] A股 / 港股通，决定手续费费率和建议卖出价的目标收益率。
 * [sortOrder] 是首页卡片的上下位置，越小越靠上；新建的股票排在最前面。
 */
data class Stock(
    val id: Long = 0,
    val name: String,
    val code: String = "",
    val accountId: Long = 1L,
    val stockType: StockType = StockType.A_SHARE,
    val sortOrder: Int = 0,
    val createdAt: String = ""
)
