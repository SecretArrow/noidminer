package org.secretarrow.noidminer.pow

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicReference

/**
 * Runtime guards that pause/resume mining based on device condition:
 *  - thermal status (PowerManager.THERMAL_STATUS_*)
 *  - battery level + charging state + battery-saver mode
 *
 * The guards expose a single [GuardSnapshot] the service watches; the
 * policy is deterministic and unit-testable via [GuardPolicy].
 */
object Guards {
    data class GuardSnapshot(
        val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
        val batteryPct: Int = 100,
        val charging: Boolean = false,
        val batterySaver: Boolean = false,
    )

    data class PolicyConfig(
        val pauseAtThermal: Int = PowerManager.THERMAL_STATUS_SEVERE,
        val throttleAtThermal: Int = PowerManager.THERMAL_STATUS_MODERATE,
        val pauseAtBatteryPct: Int = 15,
        val onlyWhenCharging: Boolean = false,
        val respectBatterySaver: Boolean = true,
    )

    sealed interface Decision {
        data object KeepMining : Decision

        data class Pause(
            val reason: String,
        ) : Decision

        data class Throttle(
            val reason: String,
        ) : Decision
    }

    /** Pure policy — easy to unit test. */
    fun evaluate(snapshot: GuardSnapshot, config: PolicyConfig): Decision {
        if (snapshot.thermalStatus >= config.pauseAtThermal) {
            return Decision.Pause("thermal ${thermalLabel(snapshot.thermalStatus)}")
        }
        if (config.onlyWhenCharging && !snapshot.charging) {
            return Decision.Pause("not charging (only-when-charging)")
        }
        if (config.respectBatterySaver && snapshot.batterySaver) {
            return Decision.Pause("battery saver on")
        }
        if (!snapshot.charging && snapshot.batteryPct <= config.pauseAtBatteryPct) {
            return Decision.Pause("battery ${snapshot.batteryPct}%")
        }
        if (snapshot.thermalStatus >= config.throttleAtThermal) {
            return Decision.Throttle("thermal ${thermalLabel(snapshot.thermalStatus)}")
        }
        return Decision.KeepMining
    }

    fun thermalLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown($status)"
    }

    /** Reads the current device condition snapshot. */
    fun snapshot(context: Context): GuardSnapshot {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).let {
            if (it in 1..100) it else 100
        }
        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val saver = pm.isPowerSaveMode
        // currentThermalStatus requires API 29; older devices report "none".
        val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) {
            pm.currentThermalStatus
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
        return GuardSnapshot(
            thermalStatus = thermal,
            batteryPct = level,
            charging = charging,
            batterySaver = saver,
        )
    }
}

/** Small holder so the service can keep the latest snapshot around. */
class GuardMonitor(
    private val context: Context,
) {
    val latest = AtomicReference(Guards.GuardSnapshot())

    fun refresh(): Guards.GuardSnapshot = Guards.snapshot(context).also { latest.set(it) }
}
