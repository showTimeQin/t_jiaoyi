package com.tjiaoyi.ledger.data.model

import android.content.Context
import com.tjiaoyi.ledger.data.db.DatabaseHelper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 港股通「按日期自动带出汇率」的查表器。
 *
 * 数据来源：上海证券交易所「沪港通-港股通信息披露-结算汇兑」逐日公布的
 * 买入 / 卖出结算汇兑比率（深交所公布的数值相同，故只需一张表）。
 * App 打包时把 2014-11-17 起的全量历史数据放进 assets/hk_settlement_rates.csv，
 * 历史日期**完全离线**查表，不联网。
 *
 * 内置表停在打包当天。录入记录时若买卖日期晚于本地最新可用交易日，[syncForDate]
 * 会按该日期区间向上交所查一次，把之后新公布的交易日写进本地 fx_rates 覆盖表，
 * 下次再查同一天就直接命中本地表。查不到 / 断网 / 接口失败都不阻塞录入，
 * 一律回退到不晚于该日期的最近一个交易日。
 *
 * 查表顺序：用户自定义覆盖值（含自动补入的） > 内置表精确命中 >
 * 不晚于该日期的最近一个交易日。用户可以在汇率表页按交割单补录 / 覆盖任意日期，
 * 自定义值优先，同步时也不会覆盖它。
 */
object FxRateRepository {

    private const val ASSET_NAME = "hk_settlement_rates.csv"
    /** 同一日期 60 秒内只联网一次：买、卖两侧会同时触发，避免重复请求 */
    private const val SYNC_CACHE_MS = 60_000L
    private const val EARLIEST_DATE = "20141117"

    private var loaded = false
    private var builtin: List<FxRate> = emptyList()
    private var builtinMap: Map<String, FxRate> = emptyMap()
    private var builtinDates: List<String> = emptyList()

    private var lastSyncKey = ""
    private var lastSyncTime = 0L
    private var lastSyncResult: FxSyncResult? = null

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        val list = mutableListOf<FxRate>()
        try {
            context.applicationContext.assets.open(ASSET_NAME).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val parts = line.split(',')
                    if (parts.size != 3) return@forEach
                    val buy = parts[1].toDoubleOrNull()
                    val sell = parts[2].toDoubleOrNull()
                    val date = parts[0].trim()
                    if (date.length == 8 && buy != null && sell != null && buy > 0.0 && sell > 0.0) {
                        list.add(FxRate(date = date, buyRate = buy, sellRate = sell))
                    }
                }
            }
        } catch (_: Exception) {
            // assets 读不到时保持空表，调用方会回退到设置页的全局汇率，不让 App 崩
        }
        builtin = list.sortedBy { it.date }
        builtinMap = builtin.associateBy { it.date }
        builtinDates = builtin.map { it.date }
        loaded = true
    }

    fun count(context: Context): Int {
        load(context)
        return builtin.size
    }

    fun firstDate(context: Context): String {
        load(context)
        return builtinDates.firstOrNull() ?: ""
    }

    fun lastDate(context: Context): String {
        load(context)
        return builtinDates.lastOrNull() ?: ""
    }

    /** 本地（内置表 + 自定义 / 自动补入的覆盖表）目前最新可用的交易日，yyyyMMdd。 */
    fun latestAvailableDate(context: Context, db: DatabaseHelper): String {
        val builtin = lastDate(context)
        val local = db.getAllFxRates().maxOfOrNull { it.date } ?: ""
        return if (local > builtin) local else builtin
    }

    fun lookup(context: Context, db: DatabaseHelper, dateMillis: Long, side: TradeSide): FxLookup? =
        lookupDate(context, db, dateKey(dateMillis), side)

    /**
     * @param key yyyyMMdd
     * @return null 表示内置表和自定义表都空；调用方应回退到 FeeConfig.hkFxRate
     */
    fun lookupDate(context: Context, db: DatabaseHelper, key: String, side: TradeSide): FxLookup? {
        load(context)
        if (key.length != 8) return null
        val overrides = db.getAllFxRates().associateBy { it.date }

        overrides[key]?.let {
            return FxLookup(it.rateFor(side), it.date, true, it.fromUser, it.note)
        }
        builtinMap[key]?.let {
            return FxLookup(it.rateFor(side), it.date, true, false)
        }

        // 非交易日 / 内置表之后的日期：取不晚于它的最近一条
        val allDates = if (overrides.isEmpty()) builtinDates else (builtinDates + overrides.keys).distinct()
        if (allDates.isEmpty()) return null
        val effective = allDates.filter { it <= key }.maxOrNull() ?: allDates.minOrNull() ?: return null
        val rate = overrides[effective] ?: builtinMap[effective] ?: return null
        return FxLookup(rate.rateFor(side), rate.date, false, rate.fromUser, rate.note)
    }

    /**
     * 录入页按日期自动补齐：当 [key] 晚于本地最新可用交易日时，联网查一次交易所，
     * 把区间内新公布的交易日全部写进本地覆盖表（"顺便更新进后台"）。
     *
     * 必须在 IO 线程调用。接口支持按 validDate 区间过滤，只返回该区间的数据；
     * 已存在的日期不会被覆盖，用户手工录入的值优先。失败返回 [FxSyncResult.error]，
     * 内置表仍在，App 继续离线可用。[force] 用于「按日期重取」强制跳过 60 秒缓存。
     */
    @Synchronized
    fun syncForDate(
        context: Context,
        db: DatabaseHelper,
        key: String,
        force: Boolean = false
    ): FxSyncResult {
        val lastLocal = latestAvailableDate(context, db)
        if (key.length != 8 || key <= lastLocal) {
            return FxSyncResult(added = 0, latestDate = lastLocal, needed = false)
        }

        val now = System.currentTimeMillis()
        if (!force && lastSyncKey == key && lastSyncResult != null &&
            now - lastSyncTime < SYNC_CACHE_MS
        ) {
            return lastSyncResult!!
        }
        lastSyncKey = key
        lastSyncTime = now

        val result = try {
            val from = if (lastLocal.length == 8) lastLocal else EARLIEST_DATE
            val to = if (dateKey(now) > key) dateKey(now) else key
            val rows = requestExchange(from, to)
            val existing = db.getAllFxRates().map { it.date }.toHashSet()
            var added = 0
            var latest = lastLocal
            for ((date, buy, sell) in rows) {
                if (date <= lastLocal || date in existing) continue
                if (db.upsertFxRate(
                        FxRate(date = date, buyRate = buy, sellRate = sell, note = FX_NOTE_EXCHANGE)
                    )
                ) {
                    added++
                    if (date > latest) latest = date
                }
            }
            FxSyncResult(added = added, latestDate = latest, fetched = rows.size)
        } catch (e: Exception) {
            FxSyncResult(
                added = 0,
                latestDate = lastLocal,
                error = e.message?.take(60) ?: "网络不可用"
            )
        }
        lastSyncResult = result
        return result
    }

    /** 必须在 IO 线程调用；返回 (yyyyMMdd, 买入结算汇兑比率, 卖出结算汇兑比率)。 */
    private fun requestExchange(from: String, to: String): List<Triple<String, Double, Double>> {
        val urlText = "https://query.sse.com.cn/commonSoaQuery.do" +
            "?isPagination=true&updateDate=$from&updateDateEnd=$to" +
            "&sqlId=FW_HGT_JSHDBL&pageHelp.cacheSize=1&pageHelp.pageSize=2000" +
            "&pageHelp.pageNo=1&pageHelp.beginPage=1&pageHelp.endPage=1"
        val conn = URL(urlText).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
            )
            conn.setRequestProperty("Referer", "https://www.sse.com.cn/")
            conn.connectTimeout = 12_000
            conn.readTimeout = 15_000
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val rows = JSONObject(body).optJSONArray("result")
                ?: throw IllegalStateException("接口未返回数据")

            val out = mutableListOf<Triple<String, Double, Double>>()
            for (i in 0 until rows.length()) {
                val obj = rows.optJSONObject(i) ?: continue
                val date = obj.optString("validDate").trim().filter { it.isDigit() }.take(8)
                if (date.length != 8) continue
                val buy = obj.optString("buyPrice").trim().toDoubleOrNull()
                val sell = obj.optString("sellPrice").trim().toDoubleOrNull()
                if (buy == null || sell == null || buy <= 0.0 || sell <= 0.0) continue
                out.add(Triple(date, buy, sell))
            }
            return out
        } finally {
            conn.disconnect()
        }
    }
}

/** 一次按日期自动同步的结果，日期均为 yyyyMMdd。 */
data class FxSyncResult(
    /** 实际写入本地覆盖表的新交易日数 */
    val added: Int,
    /** 同步后本地可用的最新交易日 */
    val latestDate: String,
    /** 接口返回的记录条数，用于排错 */
    val fetched: Int = 0,
    /** 联网失败原因；null 表示成功 */
    val error: String? = null,
    /** false 表示日期在本地表覆盖范围内，根本没联网 */
    val needed: Boolean = true
)
