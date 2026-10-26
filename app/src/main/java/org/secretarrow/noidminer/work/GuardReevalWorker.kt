package org.secretarrow.noidminer.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.secretarrow.noidminer.di.ServiceLocator
import org.secretarrow.noidminer.pow.Guards
import java.util.concurrent.TimeUnit

/**
 * Guard re-evaluation cronjob — setiap 15 menit, cek thermal + battery +
 * battery-saver. Kalau guard menyala dan miner paused → cek apakah
 * kondisi sudah aman. Kalau ya → resume mining.
 *
 * Ini backup untuk kasus di mana MinerService loop tertidur (Doze) atau
 * HP baru saja selesai charging dan guard battery sudah clear tapi service
 * belum sempat cek.
 */
class GuardReevalWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val core = ServiceLocator.minerCore()
        val state = core.state.value
        if (!state.running || !core.paused.get()) return Result.success()
        // Jangan resume kalau user yang pause eksplisit.
        if (state.pausedReason?.startsWith("user") == true) return Result.success()

        val ctx = applicationContext
        val monitor = ServiceLocator.guardMonitor(ctx)
        val snapshot = monitor.refresh()
        val settings = ServiceLocator.settingsRepository(ctx).snapshot()
        val policy = Guards.PolicyConfig(
            pauseAtThermal = settings.pauseAtThermal,
            pauseAtBatteryPct = settings.pauseAtBatteryPct,
            onlyWhenCharging = settings.onlyWhenCharging,
            respectBatterySaver = settings.respectBatterySaver,
        )
        when (val decision = Guards.evaluate(snapshot, policy)) {
            is Guards.Decision.KeepMining, is Guards.Decision.Throttle -> {
                ServiceLocator.emitServiceLog(
                    "info",
                    "GuardReeval: kondisi aman (${snapshot.thermalStatus}, batt=${snapshot.batteryPct}%) — resume",
                )
                core.resume()
            }
            is Guards.Decision.Pause -> {
                ServiceLocator.emitServiceLog(
                    "info",
                    "GuardReeval: tetap pause — ${decision.reason}",
                )
            }
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "noid_guard_reeval"

        fun schedule(context: Context) {
            val constraints = Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()
            val req = PeriodicWorkRequestBuilder<GuardReevalWorker>(
                15,
                TimeUnit.MINUTES,
            ).setConstraints(constraints)
                .addTag("noid_cron")
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }
}
