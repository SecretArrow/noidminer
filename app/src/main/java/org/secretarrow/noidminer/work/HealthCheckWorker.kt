package org.secretarrow.noidminer.work

import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.secretarrow.noidminer.di.ServiceLocator
import org.secretarrow.noidminer.pow.MinerService
import java.util.concurrent.TimeUnit

/**
 * Health-check cronjob — berjalan setiap 15 menit (interval minimum WorkManager).
 *
 * Tanggung jawab:
 *  1. Jika user sebelumnya memulai mining (state running=true di MinerCore)
 *     tapi MinerService sudah mati (OS kill, crash, OOM) → restart service.
 *  2. Jika guards menyala (thermal/battery) dan miner paused → cek apakah
 *     kondisi sudah aman, kalau ya resume.
 *  3. Log heartbeat ke service log buffer supaya pengguna tahu cronjob hidup.
 *
 * Tidak pernah memulai mining kalau user tidak pernah start (state.running=false).
 */
class HealthCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val core = ServiceLocator.minerCore()
        val state = core.state.value
        val ctx = applicationContext

        // Case 1: User believes mining is running, but the foreground service
        // is gone (Android killed it after app-swipe, OOM, or system reboot).
        // Bring it back to life — START_STICKY alone isn't enough on modern
        // Android (Doze + app standby buckets kill services aggressively).
        if (state.running) {
            val alive = isMinerServiceAlive(ctx)
            if (!alive) {
                ServiceLocator.emitServiceLog(
                    "warn",
                    "HealthCheck: MinerService hilang — restart foreground service",
                )
                runCatching {
                    ctx.startForegroundService(
                        Intent(ctx, MinerService::class.java),
                    )
                }.onFailure { t ->
                    ServiceLocator.emitServiceLog(
                        "error",
                        "HealthCheck: gagal restart service — ${t.message}",
                    )
                }
            } else if (core.paused.get()) {
                // Case 2: paused by guards — cek apakah kondisi sudah aman.
                // Jika guard bersih, Worker tidak langsung resume; biarkan
                // MinerService loop yang handle (lebih akurat, dia punya
                // snapshot thermal realtime). Cukup log saja.
                ServiceLocator.emitServiceLog(
                    "info",
                    "HealthCheck: service hidup, paused (${state.pausedReason ?: "?"})",
                )
            } else {
                ServiceLocator.emitServiceLog(
                    "info",
                    "HealthCheck: OK — ${state.backend} h=${state.hashrateHps.toLong()} H/s",
                )
            }
        }

        return Result.success()
    }

    private fun isMinerServiceAlive(context: Context): Boolean {
        val mgr = context.getSystemService(Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return mgr
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == MinerService::class.java.name }
    }

    companion object {
        const val UNIQUE_NAME = "noid_health_check"

        /** Schedule the periodic worker — idempotent (KEEP policy). */
        fun schedule(context: Context) {
            val constraints = Constraints
                .Builder()
                .setRequiresBatteryNotLow(false) // cronjob juga jalan saat baterai low
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()
            val req = PeriodicWorkRequestBuilder<HealthCheckWorker>(
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
