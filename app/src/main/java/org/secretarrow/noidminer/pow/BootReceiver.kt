package org.secretarrow.noidminer.pow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.secretarrow.noidminer.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts mining after a reboot when auto-start is enabled.
 * The receiver stays disabled by default (manifest @bool) and is only
 * enabled programmatically when the user turns auto-start on.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = ServiceLocator.settingsRepository(context).snapshot()
                if (settings.autoStartOnBoot) {
                    context.startForegroundService(
                        Intent(context, MinerService::class.java)
                            .setAction(MinerService.ACTION_RESUME),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun setEnabled(context: Context, enabled: Boolean) {
            val pm = context.packageManager
            val component = android.content.ComponentName(context, BootReceiver::class.java)
            pm.setComponentEnabledSetting(
                component,
                if (enabled) {
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }
    }
}
