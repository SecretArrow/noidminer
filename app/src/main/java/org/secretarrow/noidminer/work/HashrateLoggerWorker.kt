package org.secretarrow.noidminer.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import org.secretarrow.noidminer.di.ServiceLocator
import java.util.concurrent.TimeUnit

/**
 * Hashrate logger cronjob — setiap 30 menit, ambil snapshot hashrate + shares
 * dan emit ke service log buffer. Pengguna bisa lihat riwayat di tab Log
 * ("info" level) untuk audit atau debugging koneksi pool yang drop.
 *
 * Cronjob ini sengaja TIDAK menulis ke disk — log buffer in-memory cukup,
 * karena data ini cuma untuk konsumsi UI, bukan forensik.
 */
class HashrateLoggerWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val core = ServiceLocator.minerCore()
        val s = core.state.value
        if (!s.running) {
            // Idle — skip logging supaya buffer tidak penuh dengan noise.
            return Result.success()
        }
        val fmt = org.secretarrow.noidminer.util.Format
        ServiceLocator.emitServiceLog(
            "info",
            "Snapshot: ${fmt.hashrate(s.hashrateHps)} | " +
                "acc=${s.sharesAccepted} rej=${s.sharesRejected} | " +
                "hashes=${fmt.compact(s.hashesTried)} | " +
                "backend=${s.backend} h=${s.currentHeight}",
        )
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "noid_hashrate_logger"

        fun schedule(context: Context) {
            val constraints = Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()
            val req = PeriodicWorkRequestBuilder<HashrateLoggerWorker>(
                30,
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
