package com.tjiaoyi.ledger.data.model

/**
 * 分账户。每只股票归属一个账户，做T记录通过 stock_id 间接归属。
 *
 * [ALL_ACCOUNTS_ID] 是「总账户」的哨兵值，它不是数据库里的一行，
 * 只是把全部分账户的数据按股票代码合并后展示出来的只读视图。
 */
const val ALL_ACCOUNTS_ID = -1L
const val DEFAULT_ACCOUNT_NAME = "默认账户"

data class Account(
    val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: String = ""
) {
    val isDefault: Boolean get() = id == 1L
}

/**
 * 总账户合并用的分组键：有代码按代码，没代码按名称。
 * 同一只股票在两个分账户里都建过时靠它合成一张卡。
 */
fun stockGroupKey(stock: Stock): String =
    stock.code.trim().ifBlank { stock.name.trim() }.uppercase()
