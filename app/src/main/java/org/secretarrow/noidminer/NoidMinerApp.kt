package org.secretarrow.noidminer

import android.app.Application
import org.secretarrow.noidminer.di.ServiceLocator
import org.secretarrow.noidminer.pow.NativePow
import org.secretarrow.noidminer.work.CronScheduler

class NoidMinerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Warm the native cores so the backend label is ready on first paint.
        NativePow.ensureLoaded()
        ServiceLocator.hardwareInfo()
        // Schedule cronjobs (health-check, hashrate logger, guard re-eval).
        // Idempoten — aman dipanggil setiap cold start.
        CronScheduler.scheduleAll(this)
    }
}
