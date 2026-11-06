package com.tjiaoyi.ledger.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tjiaoyi.ledger.data.model.ALL_ACCOUNTS_ID
import com.tjiaoyi.ledger.data.model.Account
import com.tjiaoyi.ledger.data.model.CLOSED_TRADE_ORDER
import com.tjiaoyi.ledger.data.model.Currency
import com.tjiaoyi.ledger.data.model.DEFAULT_ACCOUNT_NAME
import com.tjiaoyi.ledger.data.model.FX_NOTE_EXCHANGE
import com.tjiaoyi.ledger.data.model.FxRate
import com.tjiaoyi.ledger.data.model.Stock
import com.tjiaoyi.ledger.data.model.StockType
import com.tjiaoyi.ledger.data.model.Trade
import com.tjiaoyi.ledger.data.model.detectStockType
import com.tjiaoyi.ledger.data.model.nowStamp
import com.tjiaoyi.ledger.data.model.parseDateOrNull
import com.tjiaoyi.ledger.data.model.stockGroupKey

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "t_ledger.db"
        // 2：新增 accounts 表，stocks 加 account_id / stock_type 两列。
        // 3：新增 fx_rates（用户自定义按日期汇率），trades 加币种 / 汇率 / 原币值共 7 列。
        // 必须提版本号，SQLiteOpenHelper 才会调 onUpgrade，自愈补齐逻辑才有机会跑。
        private const val DATABASE_VERSION = 3

        const val TABLE_STOCKS = "stocks"
        const val TABLE_TRADES = "trades"
        const val TABLE_ACCOUNTS = "accounts"
        const val TABLE_FX_RATES = "fx_rates"

        const val COLUMN_ID = "id"
        const val COLUMN_NAME = "name"
        const val COLUMN_CODE = "code"
        const val COLUMN_SORT_ORDER = "sort_order"
        const val COLUMN_CREATED_AT = "created_at"

        const val COLUMN_ACCOUNT_ID = "account_id"
        const val COLUMN_STOCK_TYPE = "stock_type"

        const val COLUMN_STOCK_ID = "stock_id"
        const val COLUMN_BUY_PRICE = "buy_price"
        const val COLUMN_BUY_AMOUNT = "buy_amount"
        const val COLUMN_BUY_FEE = "buy_fee"
        const val COLUMN_BUY_TIME = "buy_time"
        const val COLUMN_SELL_PRICE = "sell_price"
        const val COLUMN_SELL_AMOUNT = "sell_amount"
        const val COLUMN_SELL_FEE = "sell_fee"
        const val COLUMN_SELL_TIME = "sell_time"

        // 港币录入留痕：库里的价 / 金额始终是人民币，这些字段只用于回看和编辑还原
        const val COLUMN_INPUT_CURRENCY = "input_currency"
        const val COLUMN_BUY_FX_RATE = "buy_fx_rate"
        const val COLUMN_SELL_FX_RATE = "sell_fx_rate"
        const val COLUMN_BUY_PRICE_ORIGINAL = "buy_price_original"
        const val COLUMN_BUY_AMOUNT_ORIGINAL = "buy_amount_original"
        const val COLUMN_SELL_PRICE_ORIGINAL = "sell_price_original"
        const val COLUMN_SELL_AMOUNT_ORIGINAL = "sell_amount_original"

        // 用户自己维护的按日期结算汇率（覆盖内置表）
        const val COLUMN_RATE_DATE = "rate_date"
        const val COLUMN_BUY_RATE = "buy_rate"
        const val COLUMN_SELL_RATE = "sell_rate"
        const val COLUMN_NOTE = "note"

        const val DEFAULT_ACCOUNT_ID = 1L

        private val CREATE_ACCOUNTS_SQL = """
            CREATE TABLE $TABLE_ACCOUNTS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT NOT NULL,
                $COLUMN_SORT_ORDER INTEGER NOT NULL DEFAULT 0,
                $COLUMN_CREATED_AT TEXT NOT NULL
            )
        """.trimIndent()

        private val CREATE_STOCKS_SQL = """
            CREATE TABLE $TABLE_STOCKS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT NOT NULL,
                $COLUMN_CODE TEXT NOT NULL DEFAULT '',
                $COLUMN_ACCOUNT_ID INTEGER NOT NULL DEFAULT $DEFAULT_ACCOUNT_ID,
                $COLUMN_STOCK_TYPE TEXT NOT NULL DEFAULT 'A',
                $COLUMN_SORT_ORDER INTEGER NOT NULL DEFAULT 0,
                $COLUMN_CREATED_AT TEXT NOT NULL
            )
        """.trimIndent()

        private val CREATE_TRADES_SQL = """
            CREATE TABLE $TABLE_TRADES (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_STOCK_ID INTEGER NOT NULL,
                $COLUMN_BUY_PRICE REAL NOT NULL DEFAULT 0,
                $COLUMN_BUY_AMOUNT REAL NOT NULL DEFAULT 0,
                $COLUMN_BUY_FEE REAL NOT NULL DEFAULT 0,
                $COLUMN_BUY_TIME TEXT NOT NULL DEFAULT '',
                $COLUMN_SELL_PRICE REAL DEFAULT NULL,
                $COLUMN_SELL_AMOUNT REAL DEFAULT NULL,
                $COLUMN_SELL_FEE REAL DEFAULT NULL,
                $COLUMN_SELL_TIME TEXT DEFAULT NULL,
                $COLUMN_CREATED_AT TEXT NOT NULL,
                $COLUMN_INPUT_CURRENCY TEXT NOT NULL DEFAULT 'CNY',
                $COLUMN_BUY_FX_RATE REAL NOT NULL DEFAULT 0,
                $COLUMN_SELL_FX_RATE REAL NOT NULL DEFAULT 0,
                $COLUMN_BUY_PRICE_ORIGINAL REAL DEFAULT NULL,
                $COLUMN_BUY_AMOUNT_ORIGINAL REAL DEFAULT NULL,
                $COLUMN_SELL_PRICE_ORIGINAL REAL DEFAULT NULL,
                $COLUMN_SELL_AMOUNT_ORIGINAL REAL DEFAULT NULL,
                FOREIGN KEY ($COLUMN_STOCK_ID) REFERENCES $TABLE_STOCKS($COLUMN_ID) ON DELETE CASCADE
            )
        """.trimIndent()

        /** 用户自己补的按日期结算汇率，优先于 assets 里的内置历史表。 */
        private val CREATE_FX_RATES_SQL = """
            CREATE TABLE $TABLE_FX_RATES (
                $COLUMN_RATE_DATE TEXT PRIMARY KEY,
                $COLUMN_BUY_RATE REAL NOT NULL DEFAULT 0,
                $COLUMN_SELL_RATE REAL NOT NULL DEFAULT 0,
                $COLUMN_NOTE TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent()

        /**
         * 当前版本期望的全部列。升级时按「列是否真的存在」逐列补齐，而不是只比版本号，
         * 这样即使版本号被改过、或老库缺列也能自愈。ALTER TABLE ADD COLUMN 要求默认值，所以列定义都带 DEFAULT。
         */
        private val EXPECTED_COLUMNS = listOf(
            Triple(TABLE_ACCOUNTS, COLUMN_NAME, "TEXT NOT NULL DEFAULT ''"),
            Triple(TABLE_ACCOUNTS, COLUMN_SORT_ORDER, "INTEGER NOT NULL DEFAULT 0"),
            Triple(TABLE_ACCOUNTS, COLUMN_CREATED_AT, "TEXT NOT NULL DEFAULT ''"),
            Triple(TABLE_STOCKS, COLUMN_NAME, "TEXT NOT NULL DEFAULT ''"),
            Triple(TABLE_STOCKS, COLUMN_CODE, "TEXT NOT NULL DEFAULT ''"),
            Triple(TABLE_STOCKS, COLUMN_ACCOUNT_ID, "INTEGER NOT NULL DEFAULT $DEFAULT_ACCOUNT_ID"),
            Triple(TABLE_STOCKS, COLUMN_STOCK_TYPE, "TEXT NOT NULL DEFAULT 'A'"),
            Triple(TABLE_STOCKS, COLUMN_SORT_ORDER, "INTEGER NOT NULL DEFAULT 0"),
            Triple(TABLE_STOCKS, COLUMN_CREATED_AT, "TEXT NOT NULL DEFAULT ''"),
            Triple(TABLE_TRADES, COLUMN_STOCK_ID, "INTEGER NOT NULL DEFAULT 0"),
            Triple(TABLE_TRADES, COLUMN_BUY_PRICE, "REAL NOT NULL DEFAULT 0"),
            Triple(TABLE_TRADES, COLUMN_BUY_AMOUNT, "REAL NOT NULL DEFAULT 0"),
            Triple(TABLE_TRADES, COLUMN_BUY_FEE, "REAL NOT NULL DEFAULT 0"),
            Triple(TABLE_TRADES, COLUMN_BUY_TIME, "TEXT NOT NULL DEFAULT ''"),
            Triple(TABLE_TRADES, COLUMN_SELL_PRICE, "REAL DEFAULT NULL"),
            Triple(TABLE_TRADES, COLUMN_SELL_AMOUNT, "REAL DEFAULT NULL"),
            Triple(TABLE_TRADES, COLUMN_SELL_FEE, "REAL DEFAULT NULL"),
            Triple(TABLE_TRADES, COLUMN_SELL_TIME, "TEXT DEFAULT NULL"),
            Triple(TABLE_TRADES, COLUMN_CREATED_AT, "TEXT NOT NULL DEFAULT ''"),
            Triple(TABLE_TRADES, COLUMN_INPUT_CURRENCY, "TEXT NOT NULL DEFAULT 'CNY'"),
            Triple(TABLE_TRADES, COLUMN_BUY_FX_RATE, "REAL NOT NULL DEFAULT 0"),
            Triple(TABLE_TRADES, COLUMN_SELL_FX_RATE, "REAL NOT NULL DEFAULT 0"),
            Triple(TABLE_TRADES, COLUMN_BUY_PRICE_ORIGINAL, "REAL DEFAULT NULL"),
            Triple(TABLE_TRADES, COLUMN_BUY_AMOUNT_ORIGINAL, "REAL DEFAULT NULL"),
            Triple(TABLE_TRADES, COLUMN_SELL_PRICE_ORIGINAL, "REAL DEFAULT NULL"),
            Triple(TABLE_TRADES, COLUMN_SELL_AMOUNT_ORIGINAL, "REAL DEFAULT NULL"),
            Triple(TABLE_FX_RATES, COLUMN_BUY_RATE, "REAL NOT NULL DEFAULT 0"),
            Triple(TABLE_FX_RATES, COLUMN_SELL_RATE, "REAL NOT NULL DEFAULT 0"),
            Triple(TABLE_FX_RATES, COLUMN_NOTE, "TEXT NOT NULL DEFAULT ''")
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        upgradeSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        upgradeSchema(db)
        if (oldVersion < 2) backfillStockTypes(db)
    }

    /** 装回过旧版本的 APK 时不让它崩，尽量把结构补齐。 */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        upgradeSchema(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    private fun upgradeSchema(db: SQLiteDatabase) {
        createTableIfMissing(db, TABLE_ACCOUNTS, CREATE_ACCOUNTS_SQL)
        createTableIfMissing(db, TABLE_STOCKS, CREATE_STOCKS_SQL)
        createTableIfMissing(db, TABLE_TRADES, CREATE_TRADES_SQL)
        createTableIfMissing(db, TABLE_FX_RATES, CREATE_FX_RATES_SQL)

        for ((table, column, definition) in EXPECTED_COLUMNS) {
            if (!hasColumn(db, table, column)) {
                db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
            }
        }
        ensureDefaultAccount(db)
    }

    /** 老股票按代码自动识别 A股 / 港股通，只在第一次升级时跑一次。 */
    private fun backfillStockTypes(db: SQLiteDatabase) {
        val pairs = mutableListOf<Pair<Long, String>>()
        db.query(TABLE_STOCKS, arrayOf(COLUMN_ID, COLUMN_CODE), null, null, null, null, null).use { c ->
            val idIndex = c.getColumnIndexOrThrow(COLUMN_ID)
            val codeIndex = c.getColumnIndexOrThrow(COLUMN_CODE)
            while (c.moveToNext()) pairs.add(c.getLong(idIndex) to (c.getString(codeIndex) ?: ""))
        }
        for ((id, code) in pairs) {
            if (detectStockType(code) == StockType.HK_CONNECT) {
                val values = ContentValues().apply { put(COLUMN_STOCK_TYPE, StockType.HK_CONNECT.key) }
                db.update(TABLE_STOCKS, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
            }
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table)
        ).use { it.moveToFirst() }

    private fun createTableIfMissing(db: SQLiteDatabase, table: String, createSql: String) {
        if (!tableExists(db, table)) db.execSQL(createSql)
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        if (!tableExists(db, table)) return false
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ 账户

    /** 保证至少有一个账户，且 id=1 的「默认账户」存在。返回默认账户 id。 */
    fun ensureDefaultAccount(db: SQLiteDatabase = writableDatabase): Long {
        val cursor = db.query(TABLE_ACCOUNTS, arrayOf(COLUMN_ID), null, null, null, null, "$COLUMN_ID ASC")
        val ids = mutableListOf<Long>()
        cursor.use {
            val index = it.getColumnIndexOrThrow(COLUMN_ID)
            while (it.moveToNext()) ids.add(it.getLong(index))
        }
        if (ids.isEmpty()) {
            val values = ContentValues().apply {
                put(COLUMN_ID, DEFAULT_ACCOUNT_ID)
                put(COLUMN_NAME, DEFAULT_ACCOUNT_NAME)
                put(COLUMN_SORT_ORDER, 0)
                put(COLUMN_CREATED_AT, nowStamp())
            }
            db.insert(TABLE_ACCOUNTS, null, values)
            return DEFAULT_ACCOUNT_ID
        }
        if (!ids.contains(DEFAULT_ACCOUNT_ID)) {
            // 备份里没有默认账户（比如被删过）：补一个 id=1 的默认账户。
            // 这里不去改已有账户的名字，恢复备份时才能原样保留用户自定义的账户名。
            val values = ContentValues().apply {
                put(COLUMN_ID, DEFAULT_ACCOUNT_ID)
                put(COLUMN_NAME, DEFAULT_ACCOUNT_NAME)
                put(COLUMN_SORT_ORDER, 0)
                put(COLUMN_CREATED_AT, nowStamp())
            }
            db.insert(TABLE_ACCOUNTS, null, values)
        }
        return DEFAULT_ACCOUNT_ID
    }

    fun defaultAccountId(): Long = ensureDefaultAccount()

    fun getAllAccounts(): List<Account> {
        ensureDefaultAccount()
        val list = mutableListOf<Account>()
        val cursor = readableDatabase.query(
            TABLE_ACCOUNTS, null, null, null, null, null,
            "$COLUMN_SORT_ORDER ASC, $COLUMN_ID ASC"
        )
        cursor.use {
            while (it.moveToNext()) list.add(readAccount(it))
        }
        return list
    }

    fun getAccount(id: Long): Account? {
        val cursor = readableDatabase.query(
            TABLE_ACCOUNTS, null, "$COLUMN_ID = ?", arrayOf(id.toString()), null, null, null
        )
        cursor.use { if (it.moveToFirst()) return readAccount(it) }
        return null
    }

    fun insertAccount(name: String): Long {
        val values = ContentValues().apply {
            put(COLUMN_NAME, name)
            put(COLUMN_SORT_ORDER, nextAccountSortOrder())
            put(COLUMN_CREATED_AT, nowStamp())
        }
        return writableDatabase.insert(TABLE_ACCOUNTS, null, values)
    }

    fun updateAccount(account: Account): Int {
        val values = ContentValues().apply { put(COLUMN_NAME, account.name) }
        return writableDatabase.update(
            TABLE_ACCOUNTS, values, "$COLUMN_ID = ?", arrayOf(account.id.toString())
        )
    }

    /**
     * 删除分账户。默认账户（id=1）不允许删除。
     * 该账户下的股票**不会被删**，而是整体挪到默认账户，避免误删做T记录。
     */
    fun deleteAccount(id: Long): Boolean {
        if (id == DEFAULT_ACCOUNT_ID) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put(COLUMN_ACCOUNT_ID, DEFAULT_ACCOUNT_ID) }
            db.update(TABLE_STOCKS, values, "$COLUMN_ACCOUNT_ID = ?", arrayOf(id.toString()))
            db.delete(TABLE_ACCOUNTS, "$COLUMN_ID = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    fun countStocksByAccount(accountId: Long): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_STOCKS WHERE $COLUMN_ACCOUNT_ID = ?",
            arrayOf(accountId.toString())
        )
        cursor.use { if (it.moveToFirst()) return it.getInt(0) }
        return 0
    }

    private fun nextAccountSortOrder(): Int {
        val cursor = readableDatabase.rawQuery("SELECT MAX($COLUMN_SORT_ORDER) FROM $TABLE_ACCOUNTS", null)
        cursor.use { if (it.moveToFirst() && !it.isNull(0)) return it.getInt(0) + 1 }
        return 1
    }

    private fun readAccount(cursor: Cursor) = Account(
        id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
        name = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME)) ?: "",
        sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_SORT_ORDER)),
        createdAt = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)) ?: ""
    )

    // ------------------------------------------------------------------ 股票

    fun insertStock(stock: Stock): Long {
        val values = ContentValues().apply {
            put(COLUMN_NAME, stock.name)
            put(COLUMN_CODE, stock.code)
            put(COLUMN_ACCOUNT_ID, stock.accountId)
            put(COLUMN_STOCK_TYPE, stock.stockType.key)
            put(COLUMN_SORT_ORDER, stock.sortOrder)
            put(COLUMN_CREATED_AT, stock.createdAt)
        }
        return writableDatabase.insert(TABLE_STOCKS, null, values)
    }

    fun updateStock(stock: Stock): Int {
        val values = ContentValues().apply {
            put(COLUMN_NAME, stock.name)
            put(COLUMN_CODE, stock.code)
            put(COLUMN_ACCOUNT_ID, stock.accountId)
            put(COLUMN_STOCK_TYPE, stock.stockType.key)
        }
        return writableDatabase.update(TABLE_STOCKS, values, "$COLUMN_ID = ?", arrayOf(stock.id.toString()))
    }

    fun deleteStock(stockId: Long): Int =
        writableDatabase.delete(TABLE_STOCKS, "$COLUMN_ID = ?", arrayOf(stockId.toString()))

    /** [accountId] 传 ALL_ACCOUNTS_ID 表示不过滤，取全部分账户。 */
    fun getAllStocks(accountId: Long = ALL_ACCOUNTS_ID): List<Stock> {
        val list = mutableListOf<Stock>()
        val selection = if (accountId == ALL_ACCOUNTS_ID) null else "$COLUMN_ACCOUNT_ID = ?"
        val args = if (accountId == ALL_ACCOUNTS_ID) null else arrayOf(accountId.toString())
        val cursor = readableDatabase.query(
            TABLE_STOCKS, null, selection, args, null, null,
            "$COLUMN_SORT_ORDER ASC, $COLUMN_ID ASC"
        )
        cursor.use {
            while (it.moveToNext()) list.add(readStock(it))
        }
        return list
    }

    /** 总账户合并用：找出同一分组键（代码，没代码用名称）下的全部股票。 */
    fun getStocksByGroupKey(key: String): List<Stock> =
        getAllStocks().filter { stockGroupKey(it).equals(key.trim(), ignoreCase = true) }

    /** 从组里任意一只股票的 id 反查整组 id，详情页 / 统计页的合并视图用。 */
    fun getGroupStockIds(stockId: Long): List<Long> {
        val stock = getStockById(stockId) ?: return listOf(stockId)
        val ids = getStocksByGroupKey(stockGroupKey(stock)).map { it.id }
        return ids.ifEmpty { listOf(stockId) }
    }

    fun getStockById(stockId: Long): Stock? {
        val cursor = readableDatabase.query(
            TABLE_STOCKS, null, "$COLUMN_ID = ?", arrayOf(stockId.toString()), null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) return readStock(it)
        }
        return null
    }

    /** 同一账户内股票代码不允许重复；不同账户可以持有同一只股票（总账户会合并）。 */
    fun isCodeTaken(code: String, accountId: Long, excludeId: Long = 0L): Boolean {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return false
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_STOCKS " +
                "WHERE $COLUMN_CODE = ? AND $COLUMN_ACCOUNT_ID = ? AND $COLUMN_ID <> ?",
            arrayOf(trimmed, accountId.toString(), excludeId.toString())
        )
        cursor.use {
            if (it.moveToFirst()) return it.getInt(0) > 0
        }
        return false
    }

    /** 新建股票排在最前面：取该账户当前最小序号再减一。 */
    fun nextSortOrder(accountId: Long): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT MIN($COLUMN_SORT_ORDER) FROM $TABLE_STOCKS WHERE $COLUMN_ACCOUNT_ID = ?",
            arrayOf(accountId.toString())
        )
        cursor.use {
            if (it.moveToFirst() && !it.isNull(0)) return it.getInt(0) - 1
        }
        return 1
    }

    /** 拖拽排序结束后，把首页顺序按 0..n-1 落库。 */
    fun updateStockOrder(orderedIds: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            orderedIds.forEachIndexed { index, id ->
                val values = ContentValues().apply { put(COLUMN_SORT_ORDER, index) }
                db.update(TABLE_STOCKS, values, "$COLUMN_ID = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun readStock(cursor: Cursor): Stock {
        val code = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CODE)) ?: ""
        val rawType = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_STOCK_TYPE))
        val type = if (rawType.isNullOrBlank()) detectStockType(code) else StockType.fromKey(rawType)
        return Stock(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
            name = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME)) ?: "",
            code = code,
            accountId = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ACCOUNT_ID)),
            stockType = type,
            sortOrder = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_SORT_ORDER)),
            createdAt = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)) ?: ""
        )
    }

    // ------------------------------------------------------------------ 做T记录

    fun insertTrade(trade: Trade): Long {
        val values = ContentValues().apply {
            put(COLUMN_STOCK_ID, trade.stockId)
            putTradeFields(this, trade)
            put(COLUMN_CREATED_AT, trade.createdAt)
        }
        return writableDatabase.insert(TABLE_TRADES, null, values)
    }

    fun updateTrade(trade: Trade): Int {
        val values = ContentValues().apply { putTradeFields(this, trade) }
        return writableDatabase.update(
            TABLE_TRADES, values, "$COLUMN_ID = ?", arrayOf(trade.id.toString())
        )
    }

    private fun putTradeFields(values: ContentValues, trade: Trade) {
        values.put(COLUMN_BUY_PRICE, trade.buyPrice)
        values.put(COLUMN_BUY_AMOUNT, trade.buyAmount)
        values.put(COLUMN_BUY_FEE, trade.buyFee)
        values.put(COLUMN_BUY_TIME, trade.buyTime)

        // 卖出侧整组同生同灭：没卖出就一律写 NULL，免得留下半截脏数据
        if (trade.isClosed) {
            values.put(COLUMN_SELL_PRICE, trade.sellPrice ?: 0.0)
            values.put(COLUMN_SELL_AMOUNT, trade.sellAmount ?: 0.0)
            values.put(COLUMN_SELL_FEE, trade.sellFee ?: 0.0)
            values.put(COLUMN_SELL_TIME, trade.sellTime)
        } else {
            values.putNull(COLUMN_SELL_PRICE)
            values.putNull(COLUMN_SELL_AMOUNT)
            values.putNull(COLUMN_SELL_FEE)
            values.putNull(COLUMN_SELL_TIME)
        }

        // 港币录入留痕：只有按港币填的记录才写原币值和汇率，其余一律 NULL
        values.put(COLUMN_INPUT_CURRENCY, trade.inputCurrency.key)
        if (trade.isHkdInput) {
            values.put(COLUMN_BUY_FX_RATE, trade.buyFxRate)
            values.put(COLUMN_SELL_FX_RATE, if (trade.isClosed) trade.sellFxRate else 0.0)
            values.put(COLUMN_BUY_PRICE_ORIGINAL, trade.buyPriceOriginal ?: 0.0)
            values.put(COLUMN_BUY_AMOUNT_ORIGINAL, trade.buyAmountOriginal ?: 0.0)
            if (trade.isClosed) {
                values.put(COLUMN_SELL_PRICE_ORIGINAL, trade.sellPriceOriginal ?: 0.0)
                values.put(COLUMN_SELL_AMOUNT_ORIGINAL, trade.sellAmountOriginal ?: 0.0)
            } else {
                values.putNull(COLUMN_SELL_PRICE_ORIGINAL)
                values.putNull(COLUMN_SELL_AMOUNT_ORIGINAL)
            }
        } else {
            values.put(COLUMN_BUY_FX_RATE, 0.0)
            values.put(COLUMN_SELL_FX_RATE, 0.0)
            values.putNull(COLUMN_BUY_PRICE_ORIGINAL)
            values.putNull(COLUMN_BUY_AMOUNT_ORIGINAL)
            values.putNull(COLUMN_SELL_PRICE_ORIGINAL)
            values.putNull(COLUMN_SELL_AMOUNT_ORIGINAL)
        }
    }

    fun deleteTrade(tradeId: Long): Int =
        writableDatabase.delete(TABLE_TRADES, "$COLUMN_ID = ?", arrayOf(tradeId.toString()))

    fun getTradeById(tradeId: Long): Trade? {
        val cursor = readableDatabase.query(
            TABLE_TRADES, null, "$COLUMN_ID = ?", arrayOf(tradeId.toString()), null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) return readTrade(it)
        }
        return null
    }

    fun getTradesByStockId(stockId: Long): List<Trade> {
        val list = mutableListOf<Trade>()
        val cursor = readableDatabase.query(
            TABLE_TRADES, null, "$COLUMN_STOCK_ID = ?", arrayOf(stockId.toString()), null, null, null
        )
        cursor.use {
            while (it.moveToNext()) list.add(readTrade(it))
        }
        return list
    }

    /** 一次查出所有记录按股票分组，避免首页逐只股票查库。 */
    fun getAllTradesGroupedByStock(): Map<Long, List<Trade>> {
        val grouped = mutableMapOf<Long, MutableList<Trade>>()
        val cursor = readableDatabase.query(TABLE_TRADES, null, null, null, null, null, null)
        cursor.use {
            while (it.moveToNext()) {
                val trade = readTrade(it)
                grouped.getOrPut(trade.stockId) { mutableListOf() }.add(trade)
            }
        }
        return grouped
    }

    /**
     * 详情页第一档：**未出T**，按买入价从低到高。
     * 价格相同时按买入时间从早到晚，保证顺序稳定。
     */
    fun getOpenTrades(stockId: Long): List<Trade> =
        getTradesByStockId(stockId)
            .filter { !it.isClosed }
            .sortedWith(
                compareBy<Trade> { it.buyPrice }
                    .thenBy { parseDateOrNull(it.buyTime) ?: 0L }
                    .thenBy { it.id }
            )

    /**
     * 详情页第二档：**已T**。排序规则见 [CLOSED_TRADE_ORDER]：
     * 卖出日期从近到远，同一天按卖出价从高到低。
     */
    fun getClosedTrades(stockId: Long): List<Trade> =
        getTradesByStockId(stockId)
            .filter { it.isClosed }
            .sortedWith(CLOSED_TRADE_ORDER)

    private fun readTrade(cursor: Cursor) = Trade(
        id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
        stockId = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_STOCK_ID)),
        buyPrice = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_BUY_PRICE)),
        buyAmount = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_BUY_AMOUNT)),
        buyFee = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_BUY_FEE)),
        buyTime = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_BUY_TIME)) ?: "",
        sellPrice = cursor.nullableDouble(COLUMN_SELL_PRICE),
        sellAmount = cursor.nullableDouble(COLUMN_SELL_AMOUNT),
        sellFee = cursor.nullableDouble(COLUMN_SELL_FEE),
        sellTime = cursor.getStringOrNull(COLUMN_SELL_TIME),
        createdAt = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)) ?: "",
        inputCurrency = Currency.fromKey(cursor.getStringOrNull(COLUMN_INPUT_CURRENCY)),
        buyFxRate = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_BUY_FX_RATE)),
        sellFxRate = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_SELL_FX_RATE)),
        buyPriceOriginal = cursor.nullableDouble(COLUMN_BUY_PRICE_ORIGINAL),
        buyAmountOriginal = cursor.nullableDouble(COLUMN_BUY_AMOUNT_ORIGINAL),
        sellPriceOriginal = cursor.nullableDouble(COLUMN_SELL_PRICE_ORIGINAL),
        sellAmountOriginal = cursor.nullableDouble(COLUMN_SELL_AMOUNT_ORIGINAL)
    )

    // ------------------------------------------------------------------ 按日期汇率（用户覆盖表）

    /** 用户自己录入的日期汇率，按日期升序。 */
    fun getAllFxRates(): List<FxRate> {
        val list = mutableListOf<FxRate>()
        val cursor = readableDatabase.query(
            TABLE_FX_RATES, null, null, null, null, null, "$COLUMN_RATE_DATE ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val note = it.getString(it.getColumnIndexOrThrow(COLUMN_NOTE)) ?: ""
                list.add(
                    FxRate(
                        date = it.getString(it.getColumnIndexOrThrow(COLUMN_RATE_DATE)) ?: "",
                        buyRate = it.getDouble(it.getColumnIndexOrThrow(COLUMN_BUY_RATE)),
                        sellRate = it.getDouble(it.getColumnIndexOrThrow(COLUMN_SELL_RATE)),
                        note = note,
                        // 录入页自动补入的行不是用户手填的，界面上要分开标注
                        fromUser = note != FX_NOTE_EXCHANGE
                    )
                )
            }
        }
        return list
    }

    /** 同一天重复录入就覆盖；日期格式必须是 yyyyMMdd。 */
    fun upsertFxRate(rate: FxRate): Boolean {
        if (rate.date.length != 8 || !rate.date.all { it.isDigit() }) return false
        if (rate.buyRate <= 0.0 || rate.sellRate <= 0.0) return false
        val values = ContentValues().apply {
            put(COLUMN_RATE_DATE, rate.date)
            put(COLUMN_BUY_RATE, rate.buyRate)
            put(COLUMN_SELL_RATE, rate.sellRate)
            put(COLUMN_NOTE, rate.note)
        }
        return writableDatabase.insertWithOnConflict(
            TABLE_FX_RATES, null, values, SQLiteDatabase.CONFLICT_REPLACE
        ) > 0L
    }

    fun deleteFxRate(date: String): Int =
        writableDatabase.delete(TABLE_FX_RATES, "$COLUMN_RATE_DATE = ?", arrayOf(date))

    private fun Cursor.nullableDouble(column: String): Double? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getDouble(index)
    }

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }
}
