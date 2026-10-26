package org.secretarrow.noidminer.work

import android.content.Context
import androidx.work.WorkManager
import org.secretarrow.noidminer.di.ServiceLocator

/**
 * Pusat scheduler untuk semua cronjob NoidMiner. Dipanggil sekali dari
 * [org.secretarrow.noidminer.NoidMinerApp.onCreate] supaya cronjob ter-schedule
 * sejak HP boot (WorkManager survive reboot, jadi tidak perlu re-schedule).
 *
 * Idempoten — pakai ExistingPeriodicWorkPolicy.KEEP, jadi aman dipanggil
 * berulang. Tidak ada konflik dengan BootReceiver; BootReceiver hanya
 * start mining kalau user sebelumnya set autoStartOnBoot, cronjob ini
 * independen dan selalu jalan.
 */
object CronScheduler {
    /** Schedule semua cronjob NoidMiner — idempoten. */
    fun scheduleAll(context: Context) {
        ServiceLocator.emitServiceLog("info", "Scheduling cronjobs (health/hashlog/guard)")
        HealthCheckWorker.schedule(context)
        HashrateLoggerWorker.schedule(context)
        GuardReevalWorker.schedule(context)
    }

    /** Cancel semua cronjob — dipakai saat user uninstall atau disable mining. */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag("noid_cron")
    }
}
