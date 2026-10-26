package org.secretarrow.noidminer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "noid_settings")

/** Mining protocol selection. */
enum class MineMode { DEMO, POOL, SOLO }

/** Hashing backend preference. */
enum class BackendPref { AUTO, CPU_ONLY, GPU_ONLY }

/** Immutable settings snapshot used by the engine and service. */
data class MinerSettings(
    val mode: MineMode = MineMode.DEMO,
    val backendPref: BackendPref = BackendPref.AUTO,
    val poolUrl: String = "stratum+tcp://eu1.alphapool.tech:19610",
    val poolUser: String = "",
    val workerName: String = "noidmobile",
    val walletAddress: String = "",
    val soloRpcUrl: String = "http://127.0.0.1:10650",
    val threads: Int = 0,
    val autoStartOnBoot: Boolean = false,
    val keepCpuAwake: Boolean = true,
    val pauseAtThermal: Int = 4,
    val pauseAtBatteryPct: Int = 15,
    val onlyWhenCharging: Boolean = false,
    val respectBatterySaver: Boolean = true,
)

/** Reactive settings repository backed by DataStore. */
class MinerSettingsRepository(private val context: Context) {

    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val BACKEND = stringPreferencesKey("backend")
        val POOL_URL = stringPreferencesKey("pool_url")
        val POOL_USER = stringPreferencesKey("pool_user")
        val WORKER = stringPreferencesKey("worker_name")
        val WALLET = stringPreferencesKey("wallet_address")
        val SOLO_RPC = stringPreferencesKey("solo_rpc_url")
        val THREADS = intPreferencesKey("threads")
        val AUTO_BOOT = booleanPreferencesKey("auto_start_on_boot")
        val KEEP_AWAKE = booleanPreferencesKey("keep_cpu_awake")
        val PAUSE_THERMAL = intPreferencesKey("pause_at_thermal")
        val PAUSE_BATTERY = intPreferencesKey("pause_at_battery_pct")
        val ONLY_CHARGING = booleanPreferencesKey("only_when_charging")
        val BATTERY_SAVER = booleanPreferencesKey("respect_battery_saver")
    }

    val settings: Flow<MinerSettings> = context.dataStore.data.map { p ->
        MinerSettings(
            mode = p[Keys.MODE]?.let { runCatching { MineMode.valueOf(it) }.getOrNull() } ?: MineMode.DEMO,
            backendPref = p[Keys.BACKEND]?.let { runCatching { BackendPref.valueOf(it) }.getOrNull() }
                ?: BackendPref.AUTO,
            poolUrl = p[Keys.POOL_URL] ?: "stratum+tcp://eu1.alphapool.tech:19610",
            poolUser = p[Keys.POOL_USER].orEmpty(),
            workerName = p[Keys.WORKER] ?: "noidmobile",
            walletAddress = p[Keys.WALLET].orEmpty(),
            soloRpcUrl = p[Keys.SOLO_RPC] ?: "http://127.0.0.1:10650",
            threads = p[Keys.THREADS] ?: 0,
            autoStartOnBoot = p[Keys.AUTO_BOOT] ?: false,
            keepCpuAwake = p[Keys.KEEP_AWAKE] ?: true,
            pauseAtThermal = p[Keys.PAUSE_THERMAL] ?: 4,
            pauseAtBatteryPct = p[Keys.PAUSE_BATTERY] ?: 15,
            onlyWhenCharging = p[Keys.ONLY_CHARGING] ?: false,
            respectBatterySaver = p[Keys.BATTERY_SAVER] ?: true,
        )
    }

    suspend fun snapshot(): MinerSettings = settings.first()

    suspend fun update(transform: (MinerSettings) -> MinerSettings) {
        context.dataStore.edit { prefs ->
            val current = MinerSettings(
                mode = prefs[Keys.MODE]?.let { runCatching { MineMode.valueOf(it) }.getOrNull() }
                    ?: MineMode.DEMO,
                backendPref = prefs[Keys.BACKEND]?.let {
                    runCatching { BackendPref.valueOf(it) }.getOrNull()
                } ?: BackendPref.AUTO,
                poolUrl = prefs[Keys.POOL_URL] ?: "stratum+tcp://eu1.alphapool.tech:19610",
                poolUser = prefs[Keys.POOL_USER].orEmpty(),
                workerName = prefs[Keys.WORKER] ?: "noidmobile",
                walletAddress = prefs[Keys.WALLET].orEmpty(),
                soloRpcUrl = prefs[Keys.SOLO_RPC] ?: "http://127.0.0.1:10650",
                threads = prefs[Keys.THREADS] ?: 0,
                autoStartOnBoot = prefs[Keys.AUTO_BOOT] ?: false,
                keepCpuAwake = prefs[Keys.KEEP_AWAKE] ?: true,
                pauseAtThermal = prefs[Keys.PAUSE_THERMAL] ?: 4,
                pauseAtBatteryPct = prefs[Keys.PAUSE_BATTERY] ?: 15,
                onlyWhenCharging = prefs[Keys.ONLY_CHARGING] ?: false,
                respectBatterySaver = prefs[Keys.BATTERY_SAVER] ?: true,
            )
            val next = transform(current)
            prefs[Keys.MODE] = next.mode.name
            prefs[Keys.BACKEND] = next.backendPref.name
            prefs[Keys.POOL_URL] = next.poolUrl
            prefs[Keys.POOL_USER] = next.poolUser
            prefs[Keys.WORKER] = next.workerName
            prefs[Keys.WALLET] = next.walletAddress
            prefs[Keys.SOLO_RPC] = next.soloRpcUrl
            prefs[Keys.THREADS] = next.threads
            prefs[Keys.AUTO_BOOT] = next.autoStartOnBoot
            prefs[Keys.KEEP_AWAKE] = next.keepCpuAwake
            prefs[Keys.PAUSE_THERMAL] = next.pauseAtThermal
            prefs[Keys.PAUSE_BATTERY] = next.pauseAtBatteryPct
            prefs[Keys.ONLY_CHARGING] = next.onlyWhenCharging
            prefs[Keys.BATTERY_SAVER] = next.respectBatterySaver
        }
    }
}
