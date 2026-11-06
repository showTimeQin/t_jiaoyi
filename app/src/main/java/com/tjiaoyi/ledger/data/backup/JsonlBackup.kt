package com.tjiaoyi.ledger.data.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.tjiaoyi.ledger.data.db.DatabaseHelper
import com.tjiaoyi.ledger.data.model.FeeConfig
import com.tjiaoyi.ledger.data.model.StockType
import com.tjiaoyi.ledger.data.model.detectStockType
import com.tjiaoyi.ledger.data.model.normalizeDateKey
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份文件格式：JSONL（JSON Lines），每行一个独立的 JSON 对象，行与行之间没有逗号。
 *
 * 第一行是元信息（`_type` 为 `meta`），后面每行一条记录，用 `_type` 区分属于哪张表：
 *
 * ```
 * {"_type":"meta","app":"tj-ledger",...}
 * {"_type":"account","id":1,"name":"默认账户",...}
 * {"_type":"stock","id":1,"name":"腾讯控股","code":"00700",...}
 * {"_type":"trade","id":3,"stock_id":1,"buy_price":380.0,...}
 * {"_type":"fee_config","a_commission_rate":0.0002355,...}
 * ```
 *
 * 导出和导入都是**按字段动态处理**的：导出时读游标里真实存在的列，导入时按文件里的字段落库，
 * 所以以后给表加字段这里不用跟着改，老版本 App 读到不认识的字段会忽略、认识的字段也不会丢。
 *
 * 老备份（1.2 及以前）没有 account / fee_config 行、股票也没有 account_id / stock_type 列，
 * 导入时会自动补出默认账户并把全部股票挂进去，类型按代码识别。
 * schema 3 增加 fx_rate（用户自定义按日期结算汇率）行，以及 trade 上的港币录入字段；
 * 老备份里没有这些字段时，导入后按人民币记录处理，不影响原有数据。
 */
object JsonlBackup {

    const val FORMAT_NAME = "tj-ledger/jsonl"
    const val SCHEMA_VERSION = 3

    private const val KEY_TYPE = "_type"
    private const val KEY_ID = "id"

    private const val TYPE_META = "meta"
    private const val TYPE_ACCOUNT = "account"
    private const val TYPE_STOCK = "stock"
    private const val TYPE_TRADE = "trade"
    private const val TYPE_FEE_CONFIG = "fee_config"
    private const val TYPE_FX_RATE = "fx_rate"

    private const val TABLE_ACCOUNTS = DatabaseHelper.TABLE_ACCOUNTS
    private const val TABLE_STOCKS = DatabaseHelper.TABLE_STOCKS
    private const val TABLE_TRADES = DatabaseHelper.TABLE_TRADES
    private const val TABLE_FX_RATES = DatabaseHelper.TABLE_FX_RATES

    private const val COLUMN_ACCOUNT_ID = DatabaseHelper.COLUMN_ACCOUNT_ID
    private const val COLUMN_STOCK_TYPE = DatabaseHelper.COLUMN_STOCK_TYPE
    private const val COLUMN_STOCK_ID = DatabaseHelper.COLUMN_STOCK_ID

    /** 导出顺序：被引用的表在前，保证导入时外键能对上 */
    private val EXPORT_ORDER = listOf(
        TABLE_ACCOUNTS to TYPE_ACCOUNT,
        TABLE_STOCKS to TYPE_STOCK,
        TABLE_TRADES to TYPE_TRADE,
        TABLE_FX_RATES to TYPE_FX_RATE
    )

    private const val MAX_WARNINGS = 5

    // ------------------------------------------------------------------ 对外 API

    fun countOf(db: DatabaseHelper): BackupCounts {
        return BackupCounts(
            accounts = count(db, TABLE_ACCOUNTS),
            stocks = count(db, TABLE_STOCKS),
            trades = count(db, TABLE_TRADES),
            fxRates = count(db, TABLE_FX_RATES)
        )
    }

    /**
     * 把整库数据写成 JSONL 到 [writer]。此方法只读取数据库，不会改动任何数据。
     * [feeConfig] 作为单独一行一起导出，备份文件自带费率设置。
     */
    fun export(db: DatabaseHelper, writer: Writer, feeConfig: FeeConfig = FeeConfig()): BackupCounts {
        writer.write(metaLine(countOf(db)))
        writer.write("\n")

        val actual = mutableMapOf<String, Int>()
        for ((table, type) in EXPORT_ORDER) {
            actual[table] = writeTable(db, table, type, writer)
        }

        writer.write(feeConfigLine(feeConfig))
        writer.write("\n")
        writer.flush()

        return BackupCounts(
            accounts = actual[TABLE_ACCOUNTS] ?: 0,
            stocks = actual[TABLE_STOCKS] ?: 0,
            trades = actual[TABLE_TRADES] ?: 0,
            fxRates = actual[TABLE_FX_RATES] ?: 0
        )
    }

    /**
     * 从 JSONL 导入数据。
     *
     * - [ImportMode.REPLACE]：先清空三张表，再按文件还原，**保留原始 id**（真正的「恢复备份」）
     * - [ImportMode.APPEND]：保留现有数据，把文件内容并进来，新记录重新分配 id 并对齐外键
     *
     * 整个过程包在一个数据库事务里：中途出错会整体回滚，不会留下导了一半的库。
     *
     * @throws IllegalArgumentException 文件里找不到任何可导入的记录
     * @throws IllegalStateException 有记录写不进数据库（此时数据已整体回滚）
     */
    fun import(db: DatabaseHelper, reader: BufferedReader, mode: ImportMode): ImportResult {
        val accounts = mutableListOf<JSONObject>()
        val stocks = mutableListOf<JSONObject>()
        val trades = mutableListOf<JSONObject>()
        val fxRates = mutableListOf<JSONObject>()
        var parsedFeeConfig: FeeConfig? = null

        val warnings = mutableListOf<String>()
        var skipped = 0
        var badJsonLines = 0
        var unknownTypeLines = 0
        var metaSeen = false

        reader.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachLine

            val obj = try {
                JSONObject(line)
            } catch (e: Exception) {
                badJsonLines++
                skipped++
                return@forEachLine
            }

            when (obj.strOr(KEY_TYPE)) {
                TYPE_META -> metaSeen = true
                TYPE_ACCOUNT -> accounts.add(obj)
                TYPE_STOCK -> stocks.add(obj)
                TYPE_TRADE -> trades.add(obj)
                TYPE_FX_RATE -> fxRates.add(obj)
                TYPE_FEE_CONFIG -> parsedFeeConfig = parseFeeConfig(obj)
                else -> {
                    unknownTypeLines++
                    skipped++
                }
            }
        }

        if (badJsonLines > 0) warnings.add("有 $badJsonLines 行不是合法 JSON，已跳过")
        if (unknownTypeLines > 0) warnings.add("有 $unknownTypeLines 行记录类型未知，已跳过")
        if (!metaSeen && accounts.isEmpty() && stocks.isEmpty() && trades.isEmpty() && fxRates.isEmpty()) {
            throw IllegalArgumentException("文件里没有找到任何可导入的记录")
        }

        val keepIds = mode == ImportMode.REPLACE
        val writable = db.writableDatabase

        writable.beginTransaction()
        try {
            if (keepIds) {
                writable.delete(TABLE_TRADES, null, null)
                writable.delete(TABLE_STOCKS, null, null)
                writable.delete(TABLE_ACCOUNTS, null, null)
                writable.delete(TABLE_FX_RATES, null, null)
            }

            // 先还原文件里的账户行。覆盖导入时账户表已被清空，可以安全保留原始 id；
            // 追加导入时全部重新分配 id，文件里的 id 只作为下面外键映射表的键。
            var accountCount = 0
            val accountIdMap = mutableMapOf<Long, Long>()
            for (obj in accounts) {
                val oldId = obj.longOr(KEY_ID)
                val newId = insertRow(
                    db = writable,
                    table = TABLE_ACCOUNTS,
                    obj = obj,
                    forcedId = if (keepIds) oldId else 0L,
                    required = mapOf(
                        DatabaseHelper.COLUMN_NAME to DEFAULT_ACCOUNT_NAME_FOR_IMPORT,
                        DatabaseHelper.COLUMN_SORT_ORDER to 0,
                        DatabaseHelper.COLUMN_CREATED_AT to nowText()
                    )
                )
                if (oldId > 0L) accountIdMap[oldId] = newId
                accountCount++
            }

            // 账户行写完后才补默认账户：老备份没有 account 行时，股票会全部挂到这里；
            // 顺序颠倒的话，文件里 id=1 的账户会跟自动补的默认账户撞主键，整个导入失败。
            val defaultAccountId = db.ensureDefaultAccount(writable)

            // 追加导入时绝不能把文件里的账户 id 当成本机 id 直接用（那可能指向另一个账户），
            // 找不到映射就一律落到默认账户。
            fun resolveAccountId(raw: Long): Long {
                if (raw <= 0L) return defaultAccountId
                return accountIdMap[raw] ?: defaultAccountId
            }

            var stockCount = 0
            val stockIdMap = mutableMapOf<Long, Long>()
            for (obj in stocks) {
                val oldId = obj.longOr(KEY_ID)
                // 老备份没有 stock_type：按代码直接补出 A / HK 存进库，
                // 不留空字符串，否则下次导出还是会把空类型带出去。
                val rawType = obj.strOr(COLUMN_STOCK_TYPE)
                val resolvedType = if (rawType.isBlank()) {
                    detectStockType(obj.strOr(DatabaseHelper.COLUMN_CODE)).key
                } else {
                    StockType.fromKey(rawType).key
                }
                val newId = insertRow(
                    db = writable,
                    table = TABLE_STOCKS,
                    obj = obj,
                    forcedId = if (keepIds) oldId else 0L,
                    required = mapOf(
                        DatabaseHelper.COLUMN_NAME to "未命名股票",
                        DatabaseHelper.COLUMN_CODE to "",
                        COLUMN_STOCK_TYPE to StockType.A_SHARE.key,
                        DatabaseHelper.COLUMN_SORT_ORDER to 0,
                        DatabaseHelper.COLUMN_CREATED_AT to nowText()
                    ),
                    remap = mapOf(
                        COLUMN_ACCOUNT_ID to resolveAccountId(obj.longOr(COLUMN_ACCOUNT_ID)),
                        COLUMN_STOCK_TYPE to resolvedType
                    )
                )
                if (oldId > 0L) stockIdMap[oldId] = newId
                stockCount++
            }

            var tradeCount = 0
            for (obj in trades) {
                val oldStockId = obj.longOr(COLUMN_STOCK_ID)
                val stockId = if (keepIds) {
                    // 覆盖导入保留原始股票 id，但只接受文件里确实存在的那只股票，
                    // 否则会留下悬空外键（外键约束是开着的，会直接抛异常回滚整个导入）。
                    if (oldStockId > 0L && stockIdMap.containsKey(oldStockId)) oldStockId else null
                } else {
                    stockIdMap[oldStockId]
                }
                if (stockId == null || stockId <= 0L) {
                    skipped++
                    addWarning(warnings, "有做T记录引用的股票 $oldStockId 不在文件里，已跳过该记录")
                    continue
                }

                insertRow(
                    db = writable,
                    table = TABLE_TRADES,
                    obj = obj,
                    forcedId = if (keepIds) obj.longOr(KEY_ID) else 0L,
                    required = mapOf(
                        DatabaseHelper.COLUMN_BUY_PRICE to 0.0,
                        DatabaseHelper.COLUMN_BUY_AMOUNT to 0.0,
                        DatabaseHelper.COLUMN_BUY_FEE to 0.0,
                        DatabaseHelper.COLUMN_BUY_TIME to "",
                        DatabaseHelper.COLUMN_CREATED_AT to nowText()
                    ),
                    remap = mapOf(COLUMN_STOCK_ID to stockId)
                )
                tradeCount++
            }

            // 用户自定义的按日期汇率。追加导入时同一天以备份文件里的为准，
            // 先删掉本机同日期记录，避免主键冲突导致整个导入回滚。
            var fxRateCount = 0
            for (obj in fxRates) {
                val key = normalizeDateKey(obj.strOr(DatabaseHelper.COLUMN_RATE_DATE))
                if (key.length != 8) {
                    skipped++
                    addWarning(warnings, "有汇率记录日期格式不对，已跳过")
                    continue
                }
                if (!keepIds) {
                    writable.delete(
                        TABLE_FX_RATES,
                        "${DatabaseHelper.COLUMN_RATE_DATE} = ?",
                        arrayOf(key)
                    )
                }
                insertRow(
                    db = writable,
                    table = TABLE_FX_RATES,
                    obj = obj,
                    forcedId = 0L,
                    required = mapOf(
                        DatabaseHelper.COLUMN_BUY_RATE to 0.0,
                        DatabaseHelper.COLUMN_SELL_RATE to 0.0,
                        DatabaseHelper.COLUMN_NOTE to ""
                    ),
                    remap = mapOf(DatabaseHelper.COLUMN_RATE_DATE to key)
                )
                fxRateCount++
            }

            writable.setTransactionSuccessful()

            return ImportResult(
                counts = BackupCounts(
                    accounts = accountCount,
                    stocks = stockCount,
                    trades = tradeCount,
                    fxRates = fxRateCount
                ),
                skipped = skipped,
                warnings = warnings.take(MAX_WARNINGS),
                feeConfig = parsedFeeConfig
            )
        } finally {
            writable.endTransaction()
        }
    }

    private const val DEFAULT_ACCOUNT_NAME_FOR_IMPORT = "默认账户"

    // ------------------------------------------------------------------ 导出细节

    private fun metaLine(counts: BackupCounts): String {
        val obj = JSONObject()
        obj.put(KEY_TYPE, TYPE_META)
        obj.put("app", "tj-ledger")
        obj.put("format", FORMAT_NAME)
        obj.put("schema", SCHEMA_VERSION)
        obj.put("exported_at", nowText())
        obj.put("counts", JSONObject().apply {
            put("accounts", counts.accounts)
            put("stocks", counts.stocks)
            put("trades", counts.trades)
            put("fx_rates", counts.fxRates)
        })
        return obj.toString()
    }

    private fun feeConfigLine(config: FeeConfig): String {
        val obj = JSONObject()
        obj.put(KEY_TYPE, TYPE_FEE_CONFIG)
        obj.put("a_commission_rate", config.aCommissionRate)
        obj.put("a_min_commission", config.aMinCommission)
        obj.put("a_transfer_rate", config.aTransferRate)
        obj.put("a_stamp_rate", config.aStampRate)
        obj.put("hk_commission_rate", config.hkCommissionRate)
        obj.put("hk_min_commission", config.hkMinCommission)
        obj.put("hk_stamp_rate", config.hkStampRate)
        obj.put("hk_levy_rate", config.hkLevyRate)
        obj.put("hk_frc_rate", config.hkFrcRate)
        obj.put("hk_trading_rate", config.hkTradingRate)
        obj.put("hk_settlement_rate", config.hkSettlementRate)
        obj.put("hk_fx_rate", config.hkFxRate)
        obj.put("hk_stamp_round_up", config.hkStampRoundUpToHkd)
        obj.put("a_target_rate", config.aTargetRate)
        obj.put("hk_target_rate", config.hkTargetRate)
        return obj.toString()
    }

    private fun parseFeeConfig(obj: JSONObject): FeeConfig {
        val d = FeeConfig()
        fun rate(key: String, fallback: Double): Double {
            if (!obj.has(key)) return fallback
            val v = obj.optDouble(key, fallback)
            return if (v.isFinite() && v >= 0.0) v else fallback
        }
        return FeeConfig(
            aCommissionRate = rate("a_commission_rate", d.aCommissionRate),
            aMinCommission = rate("a_min_commission", d.aMinCommission),
            aTransferRate = rate("a_transfer_rate", d.aTransferRate),
            aStampRate = rate("a_stamp_rate", d.aStampRate),
            hkCommissionRate = rate("hk_commission_rate", d.hkCommissionRate),
            hkMinCommission = rate("hk_min_commission", d.hkMinCommission),
            hkStampRate = rate("hk_stamp_rate", d.hkStampRate),
            hkLevyRate = rate("hk_levy_rate", d.hkLevyRate),
            hkFrcRate = rate("hk_frc_rate", d.hkFrcRate),
            hkTradingRate = rate("hk_trading_rate", d.hkTradingRate),
            hkSettlementRate = rate("hk_settlement_rate", d.hkSettlementRate),
            hkFxRate = rate("hk_fx_rate", d.hkFxRate),
            hkStampRoundUpToHkd = obj.optBoolean("hk_stamp_round_up", d.hkStampRoundUpToHkd),
            aTargetRate = rate("a_target_rate", d.aTargetRate),
            hkTargetRate = rate("hk_target_rate", d.hkTargetRate)
        )
    }

    /**
     * 直接把游标里的每一行转成一个 JSON 对象，字段名和类型都按数据库真实结构来，
     * 所以当前代码没用到、但库里存在的列也会原样导出。
     */
    private fun writeTable(db: DatabaseHelper, table: String, type: String, writer: Writer): Int {
        var written = 0
        // fx_rates 表的主键是 rate_date，没有 id 列；写死 "id ASC" 会让查询直接抛异常，
        // 整个导出中途断掉。按表实际拥有的列挑排序列。
        val columns = tableColumns(db.readableDatabase, table)
        val orderBy = when {
            KEY_ID in columns -> "$KEY_ID ASC"
            DatabaseHelper.COLUMN_RATE_DATE in columns -> "${DatabaseHelper.COLUMN_RATE_DATE} ASC"
            else -> null
        }
        val cursor = db.readableDatabase.query(table, null, null, null, null, null, orderBy)

        cursor.use { c ->
            while (c.moveToNext()) {
                val obj = JSONObject()
                obj.put(KEY_TYPE, type)

                for (i in 0 until c.columnCount) {
                    val name = c.getColumnName(i)
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> obj.put(name, JSONObject.NULL)
                        Cursor.FIELD_TYPE_INTEGER -> obj.put(name, c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> {
                            val value = c.getDouble(i)
                            obj.put(name, if (value.isFinite()) value else 0.0)
                        }
                        else -> obj.put(name, c.getString(i) ?: "")
                    }
                }

                writer.write(obj.toString())
                writer.write("\n")
                written++
            }
        }
        return written
    }

    private fun count(db: DatabaseHelper, table: String): Int {
        val cursor = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null)
        cursor.use {
            if (it.moveToFirst()) return it.getInt(0)
        }
        return 0
    }

    // ------------------------------------------------------------------ 导入细节

    /**
     * 按文件里的字段把一行写进 [table]。
     *
     * @param required NOT NULL 列缺省时的兜底值，只在文件里没给这个字段时用
     * @param remap    必须覆盖的列（外键重映射）；值为 null 表示写入 NULL
     * @param forcedId 大于 0 时写入这个 id，否则交给数据库自增
     */
    private fun insertRow(
        db: SQLiteDatabase,
        table: String,
        obj: JSONObject,
        forcedId: Long,
        required: Map<String, Any> = emptyMap(),
        remap: Map<String, Any?> = emptyMap()
    ): Long {
        val columns = tableColumns(db, table)
        val values = ContentValues()

        // 1. 文件里有的字段原样落库（App 不认识的列会被忽略，但库里存在的列都会被保留）
        for (key in obj.keys()) {
            if (key == KEY_ID || key == KEY_TYPE) continue
            if (key !in columns) continue
            if (obj.isNull(key)) continue

            when (val value = obj.get(key)) {
                is Boolean -> values.put(key, if (value) 1 else 0)
                is Int -> values.put(key, value)
                is Long -> values.put(key, value)
                is Double -> values.put(key, if (value.isFinite()) value else 0.0)
                is Number -> values.put(key, value.toDouble())
                else -> values.put(key, value.toString())
            }
        }

        // 2. NOT NULL 的列缺了就补默认值，不让一行残缺数据把整个导入搞失败
        for ((key, value) in required) {
            if (key !in columns || values.containsKey(key)) continue
            putAny(values, key, value)
        }

        // 3. 外键重映射等必须覆盖的列
        for ((key, value) in remap) {
            if (key !in columns) continue
            if (value == null) values.putNull(key) else putAny(values, key, value)
        }

        // 4. 覆盖导入时保留原始 id
        if (forcedId > 0L && KEY_ID in columns) values.put(KEY_ID, forcedId)

        val rowId = db.insert(table, null, values)
        if (rowId < 0L) throw IllegalStateException("有记录写不进 $table，导入已回滚")
        // 显式指定过 id 时直接回它：某些 ROM 上 insert() 返回的 last_insert_rowid
        // 并不等于显式写入的 INTEGER PRIMARY KEY，后面的外键映射会因此错位。
        return if (forcedId > 0L) forcedId else rowId
    }

    private fun putAny(values: ContentValues, key: String, value: Any) {
        when (value) {
            is Int -> values.put(key, value)
            is Long -> values.put(key, value)
            is Double -> values.put(key, value)
            is String -> values.put(key, value)
            else -> values.put(key, value.toString())
        }
    }

    /** 读表里真实存在的列名，用于过滤文件里多余的字段 */
    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> {
        val columns = mutableSetOf<String>()
        val cursor = db.rawQuery("PRAGMA table_info($table)", null)
        cursor.use {
            val nameIndex = it.getColumnIndex("name")
            if (nameIndex < 0) return columns
            while (it.moveToNext()) {
                columns.add(it.getString(nameIndex))
            }
        }
        return columns
    }

    private fun addWarning(warnings: MutableList<String>, text: String) {
        if (warnings.size < MAX_WARNINGS) warnings.add(text)
    }

    private fun nowText(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    // ------------------------------------------------------------------ JSON 取值的兜底

    private fun JSONObject.strOr(key: String, fallback: String = ""): String {
        if (!has(key) || isNull(key)) return fallback
        return optString(key, fallback)
    }

    private fun JSONObject.longOr(key: String, fallback: Long = 0L): Long {
        if (!has(key) || isNull(key)) return fallback
        return optLong(key, fallback)
    }
}

/** 导入方式 */
enum class ImportMode {
    /** 清空现有数据后按文件还原，保留原始 id —— 相当于恢复备份 */
    REPLACE,

    /** 保留现有数据，把文件内容并进来，重新分配 id —— 相当于合并两份数据 */
    APPEND
}

/** 各表的记录条数 */
data class BackupCounts(
    val accounts: Int = 0,
    val stocks: Int = 0,
    val trades: Int = 0,
    val fxRates: Int = 0
) {
    val total: Int get() = accounts + stocks + trades + fxRates

    fun describe(): String {
        val prefix = if (accounts > 0) "账户 $accounts 个 · " else ""
        val rates = if (fxRates > 0) " · 自定义汇率 $fxRates 条" else ""
        return prefix + "股票 $stocks 只 · 做T记录 $trades 条" + rates
    }
}

/** 导入结果 */
data class ImportResult(
    val counts: BackupCounts,
    val skipped: Int,
    val warnings: List<String>,
    /** 备份里带的费率设置；老备份没有这一行时为 null，表示不动本机设置 */
    val feeConfig: FeeConfig? = null
)
