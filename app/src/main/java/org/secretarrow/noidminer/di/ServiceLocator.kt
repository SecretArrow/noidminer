package org.secretarrow.noidminer.di

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import org.secretarrow.noidminer.data.MinerSettingsRepository
import org.secretarrow.noidminer.data.Vault
import org.secretarrow.noidminer.pow.EngineSelector
import org.secretarrow.noidminer.pow.GuardMonitor
import org.secretarrow.noidminer.pow.HardwareInfo
import org.secretarrow.noidminer.pow.HardwareReader
import org.secretarrow.noidminer.pow.LogLine
import org.secretarrow.noidminer.pow.MinerCore

/**
 * Tiny hand-rolled dependency container — zero reflection, zero codegen,
 * keeps the CI build fast while remaining trivially testable.
 */
object ServiceLocator {
    private val lock = Any()

    @Volatile
    private var settingsRepo: MinerSettingsRepository? = null

    @Volatile
    private var minerCore: MinerCore? = null

    @Volatile
    private var hardwareInfo: HardwareInfo? = null

    val serviceLog = MutableSharedFlow<LogLine>(extraBufferCapacity = 512)

    /** Application context — set in [init], safe after Application.onCreate. */
    var appContext: Context? = null
        private set

    fun init(context: Context) {
        synchronized(lock) {
            if (appContext == null) {
                appContext = context.applicationContext
            }
        }
    }

    fun settingsRepository(context: Context): MinerSettingsRepository =
        settingsRepo ?: synchronized(lock) {
            settingsRepo ?: MinerSettingsRepository(context.applicationContext).also { settingsRepo = it }
        }

    fun vault(context: Context): Vault = Vault(context.applicationContext)

    fun minerCore(): MinerCore =
        minerCore ?: synchronized(lock) {
            minerCore ?: MinerCore { EngineSelector.select(preferGpu = true) }.also { minerCore = it }
        }

    fun hardwareInfo(): HardwareInfo =
        hardwareInfo ?: synchronized(lock) {
            hardwareInfo ?: HardwareReader.read().also { hardwareInfo = it }
        }

    fun guardMonitor(context: Context): GuardMonitor = GuardMonitor(context.applicationContext)

    fun emitServiceLog(level: String, message: String) {
        serviceLog.tryEmit(LogLine(System.currentTimeMillis(), level, message))
    }

    /** Test hook — clears cached singletons. */
    fun resetForTest() {
        synchronized(lock) {
            settingsRepo = null
            minerCore = null
            hardwareInfo = null
        }
    }
}
