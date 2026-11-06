package com.tjiaoyi.ledger.data.model

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 进程内的全局状态：当前查看的账户、手续费费率。
 *
 * 用 Compose 的 State 包一层，切换账户 / 改费率时各个页面会立即跟着刷新，
 * 不用一层层传回调。费率会落到 SharedPreferences，重启后仍然生效；
 * 当前选中的账户不持久化，每次打开 App 默认回到「总账户」。
 */
object AppState {
    const val PREF_NAME = "tj_ledger_prefs"

    private const val KEY_FEE_PREFIX = "fee_"
    private const val KEY_FEE_VERSION = "fee_config_version"
    private const val FEE_CONFIG_VERSION = 3
    /** 1.5 里误用过的港股通汇率、以及 1.6.0 里为凑交割单反推的拟合汇率 */
    private val OLD_HK_FX_DEFAULTS = floatArrayOf(0.8527f, 0.9435f)

    /** 当前选中的账户，ALL_ACCOUNTS_ID 表示总账户（只读汇总）。 */
    var selectedAccountId: Long by mutableStateOf(ALL_ACCOUNTS_ID)
        private set

    /** 当前费率配置。设置页保存后写回，添加做T记录页会实时重算。 */
    var feeConfig: FeeConfig by mutableStateOf(FeeConfig())
        private set

    var accountsVersion: Int by mutableStateOf(0)
        private set

    fun selectAccount(id: Long) {
        selectedAccountId = id
    }

    fun notifyAccountsChanged() {
        accountsVersion++
    }

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        migrateFeeConfig(prefs)
        feeConfig = readFeeConfig(prefs)
        // 账户不持久化，固定从总账户开始；总账户一定能用，不会出现「选中不存在的账户」。
        selectedAccountId = ALL_ACCOUNTS_ID
    }

    /**
     * 1.6.1 起港股通印花税取整改用按日期的真实结算汇兑比率，费率里的 hk_fx
     * 降级成「查不到任何日期数据时的离线兜底」，默认值也从拟合值 0.9435
     * 改成内置表最后一个交易日的真实值 0.85086。
     *
     * 老用户 prefs 里存着历史默认值时会让兜底值不对，所以升级一次：
     * 只有当保存值恰好等于某个历史默认（说明用户没手动改过）时才替换，
     * 其它自定义费率和汇率全部保留。
     */
    private fun migrateFeeConfig(prefs: android.content.SharedPreferences) {
        if (prefs.getInt(KEY_FEE_VERSION, 1) >= FEE_CONFIG_VERSION) return
        val editor = prefs.edit()
        val savedFx = prefs.getFloat(KEY_FEE_PREFIX + "hk_fx", -1f)
        if (savedFx > 0f && OLD_HK_FX_DEFAULTS.any { kotlin.math.abs(savedFx - it) < 0.0001f }) {
            editor.putFloat(KEY_FEE_PREFIX + "hk_fx", FeeConfig().hkFxRate.toFloat())
        }
        editor.putInt(KEY_FEE_VERSION, FEE_CONFIG_VERSION).apply()
    }

    fun saveFeeConfig(context: Context, config: FeeConfig) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat(KEY_FEE_PREFIX + "a_commission", config.aCommissionRate.toFloat())
            putFloat(KEY_FEE_PREFIX + "a_min", config.aMinCommission.toFloat())
            putFloat(KEY_FEE_PREFIX + "a_transfer", config.aTransferRate.toFloat())
            putFloat(KEY_FEE_PREFIX + "a_stamp", config.aStampRate.toFloat())
            putFloat(KEY_FEE_PREFIX + "hk_commission", config.hkCommissionRate.toFloat())
            putFloat(KEY_FEE_PREFIX + "hk_min", config.hkMinCommission.toFloat())
            putFloat(KEY_FEE_PREFIX + "hk_stamp", config.hkStampRate.toFloat())
            putFloat(KEY_FEE_PREFIX + "hk_levy", config.hkLevyRate.toFloat())
            putFloat(KEY_FEE_PREFIX + "hk_frc", config.hkFrcRate.toFloat())
            putFloat(KEY_FEE_PREFIX + "hk_trading", config.hkTradingRate.toFloat())
            putFloat(KEY_FEE_PREFIX + "hk_settlement", config.hkSettlementRate.toFloat())
            putFloat(KEY_FEE_PREFIX + "hk_fx", config.hkFxRate.toFloat())
            putBoolean(KEY_FEE_PREFIX + "hk_stamp_round", config.hkStampRoundUpToHkd)
            putFloat(KEY_FEE_PREFIX + "a_target", config.aTargetRate.toFloat())
            putFloat(KEY_FEE_PREFIX + "hk_target", config.hkTargetRate.toFloat())
            putInt(KEY_FEE_VERSION, FEE_CONFIG_VERSION)
            apply()
        }
        feeConfig = config
    }

    fun resetFeeConfig(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_FEE_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
        feeConfig = FeeConfig()
    }

    private fun readFeeConfig(prefs: android.content.SharedPreferences): FeeConfig {
        val d = FeeConfig()
        fun rate(key: String, fallback: Double): Double {
            if (!prefs.contains(KEY_FEE_PREFIX + key)) return fallback
            // SharedPreferences 只有 Float：0.001 读回来会是 0.0010000000475。
            // 费率最多 10 位小数，先收敛掉 Float 噪声，否则印花税按整港币
            // 向上取整时会在整数边界多跳一档。
            val raw = prefs.getFloat(KEY_FEE_PREFIX + key, fallback.toFloat()).toDouble()
            return kotlin.math.round(raw * 1e10) / 1e10
        }
        return FeeConfig(
            aCommissionRate = rate("a_commission", d.aCommissionRate),
            aMinCommission = rate("a_min", d.aMinCommission),
            aTransferRate = rate("a_transfer", d.aTransferRate),
            aStampRate = rate("a_stamp", d.aStampRate),
            hkCommissionRate = rate("hk_commission", d.hkCommissionRate),
            hkMinCommission = rate("hk_min", d.hkMinCommission),
            hkStampRate = rate("hk_stamp", d.hkStampRate),
            hkLevyRate = rate("hk_levy", d.hkLevyRate),
            hkFrcRate = rate("hk_frc", d.hkFrcRate),
            hkTradingRate = rate("hk_trading", d.hkTradingRate),
            hkSettlementRate = rate("hk_settlement", d.hkSettlementRate),
            hkFxRate = rate("hk_fx", d.hkFxRate),
            hkStampRoundUpToHkd = prefs.getBoolean(KEY_FEE_PREFIX + "hk_stamp_round", d.hkStampRoundUpToHkd),
            aTargetRate = rate("a_target", d.aTargetRate),
            hkTargetRate = rate("hk_target", d.hkTargetRate)
        )
    }
}
