package org.secretarrow.noidminer.pow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.secretarrow.noidminer.MainActivity
import org.secretarrow.noidminer.R
import org.secretarrow.noidminer.data.MinerSettings
import org.secretarrow.noidminer.data.MinerSettingsRepository
import org.secretarrow.noidminer.data.MineMode
import org.secretarrow.noidminer.data.PoolJobSource
import org.secretarrow.noidminer.data.SoloJobSource
import org.secretarrow.noidminer.data.Vault
import org.secretarrow.noidminer.di.ServiceLocator

/**
 * Foreground service that keeps mining alive in the background:
 *  - persistent notification with live hashrate + Pause/Resume/Stop actions
 *  - partial wake lock (optional) so Doze doesn't kill throughput
 *  - thermal + battery guards pause mining automatically and resume later
 */
class MinerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settingsRepo: MinerSettingsRepository
    private lateinit var core: MinerCore
    private lateinit var guardMonitor: GuardMonitor
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepo = ServiceLocator.settingsRepository(this)
        core = ServiceLocator.minerCore()
        guardMonitor = ServiceLocator.guardMonitor(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                core.pause("user")
                updateNotification()
                return START_STICKY
            }
            ACTION_RESUME -> {
                core.resume()
                updateNotification()
                return START_STICKY
            }
            ACTION_STOP -> {
                stopMining()
                return START_NOT_STICKY
            }
            else -> Unit
        }
        startForegroundCompat()
        scope.launch { runMiningLoop() }
        return START_STICKY
    }

    private suspend fun runMiningLoop() {
        val settings = settingsRepo.snapshot()
        if (core.running.get()) return
        acquireWakeLock(settings)

        val threadCount = settings.threads.takeIf { it > 0 }
            ?: ServiceLocator.hardwareInfo().suggestedThreads()
        val source = buildJobSource(settings)

        core.start(source, threadCount)
        updateNotification()

        // Watch loop: guards + hashrate refresh.
        while (core.running.get()) {
            val snapshot = guardMonitor.refresh()
            val policy = org.secretarrow.noidminer.pow.Guards.PolicyConfig(
                pauseAtThermal = settings.pauseAtThermal,
                pauseAtBatteryPct = settings.pauseAtBatteryPct,
                onlyWhenCharging = settings.onlyWhenCharging,
                respectBatterySaver = settings.respectBatterySaver,
            )
            when (val decision = Guards.evaluate(snapshot, policy)) {
                is Guards.Decision.Pause -> {
                    if (!core.paused.get()) {
                        core.pause(decision.reason)
                        updateNotification()
                    }
                }
                is Guards.Decision.Throttle -> core.resume()
                Guards.Decision.KeepMining -> {
                    if (core.paused.get() &&
                        core.state.value.pausedReason
                            ?.startsWith("user") != true
                    ) {
                        core.resume()
                        updateNotification()
                    }
                }
            }
            updateNotification()
            delay(NOTIF_REFRESH_MS)
        }
        releaseWakeLock()
        stopSelf()
    }

    private fun buildJobSource(settings: MinerSettings): JobSource {
        val vault = Vault(this)
        val logger = { level: String, message: String ->
            ServiceLocator.emitServiceLog(level, message)
        }
        return when (settings.mode) {
            MineMode.POOL -> PoolJobSource(settings.poolUrl, vault.poolUser.ifBlank { settings.poolUser }, logger)
            MineMode.SOLO -> SoloJobSource(settings.soloRpcUrl, vault.soloRpcToken, vault.walletAddress, logger)
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        when {
            // SPECIAL_USE type is only valid on API 34+.
            Build.VERSION.SDK_INT >= 34 -> startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            else -> startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = core.state.value
        val hashrate = org.secretarrow.noidminer.util.Format
            .hashrate(state.hashrateHps)
        val title = when {
            state.running && state.paused -> getString(R.string.notif_paused)
            state.running -> getString(R.string.notif_title)
            else -> getString(R.string.app_name)
        }
        val text = buildString {
            append(hashrate)
            append(" · ")
            append(state.backend.ifBlank { "idle" })
            if (state.running && !state.paused) {
                append(" · ")
                append(getString(R.string.label_shares, state.sharesAccepted, state.sharesRejected))
            }
            state.pausedReason?.let { append(" · ").append(it) }
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pausedNow = state.paused
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mining)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(action(ACTION_PAUSE, getString(R.string.action_pause)))
            .addAction(action(ACTION_RESUME, getString(R.string.action_resume)))
            .addAction(action(ACTION_STOP, getString(R.string.action_stop)))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun action(action: String, label: String): NotificationCompat.Action {
        val intent = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, MinerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action(0, label, intent)
    }

    private fun acquireWakeLock(settings: MinerSettings) {
        if (!settings.keepCpuAwake) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noidminer:mine").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    private fun stopMining() {
        core.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_mining),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_mining_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "noid_mining"
        const val NOTIF_ID = 42
        const val ACTION_PAUSE = "org.secretarrow.noidminer.PAUSE"
        const val ACTION_RESUME = "org.secretarrow.noidminer.RESUME"
        const val ACTION_STOP = "org.secretarrow.noidminer.STOP"
        private const val NOTIF_REFRESH_MS = 2_000L
        private const val WAKELOCK_MS = 24L * 60 * 60 * 1000
    }
}
