package org.secretarrow.noidminer.work

import android.content.Context
import android.util.Log
import androidx.work.WorkManager

/**
 * Pusat scheduler untuk semua cronjob NoidMiner. Dipanggil sekali dari
 * [org.secretarrow.noidminer.NoidMinerApp.onCreate] supaya cronjob ter-schedule
 * sejak HP boot (WorkManager survive reboot, jadi tidak perlu re-schedule).
 *
 * Idempoten — pakai ExistingPeriodicWorkPolicy.KEEP, jadi aman dipanggil
 * berulang. Tidak ada konflik dengan BootReceiver; BootReceiver hanya
 * start mining kalau user sebelumnya set autoStartOnBoot, cronjob ini
 * independen dan selalu jalan.
 *
 * Defensive: bungkus tiap schedule dalam runCatching supaya crash di cronjob
 * tidak pernah membawa turun Application.onCreate (kasus ekstrem: WorkManager
 * belum siap saat onCreate, mis. di unit test Robolectric).
 */
object CronScheduler {
    /** Schedule semua cronjob NoidMiner — idempoten, tidak pernah throw. */
    fun scheduleAll(context: Context) {
        runCatching { HealthCheckWorker.schedule(context) }
            .onFailure { t ->
                Log.w("CronScheduler", "HealthCheckWorker schedule failed: ${t.message}")
            }
        runCatching { HashrateLoggerWorker.schedule(context) }
            .onFailure { t ->
                Log.w("CronScheduler", "HashrateLoggerWorker schedule failed: ${t.message}")
            }
        runCatching { GuardReevalWorker.schedule(context) }
            .onFailure { t ->
                Log.w("CronScheduler", "GuardReevalWorker schedule failed: ${t.message}")
            }
    }

    /** Cancel semua cronjob — dipakai saat user uninstall atau disable mining. */
    fun cancelAll(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelAllWorkByTag("noid_cron")
        }
    }
}
