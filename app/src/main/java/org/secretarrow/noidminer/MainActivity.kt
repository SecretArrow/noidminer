package org.secretarrow.noidminer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.secretarrow.noidminer.di.ServiceLocator
import org.secretarrow.noidminer.ui.navigation.NoidNavHost
import org.secretarrow.noidminer.ui.theme.NoidTheme
import org.secretarrow.noidminer.viewmodel.MineViewModel

/**
 * Single-activity Compose app. The Mine screen wires directly into
 * [MinerService] so mining continues in the background with the
 * persistent notification, regardless of UI state.
 */
class MainActivity : ComponentActivity() {
    private val mineViewModel by lazy { MineViewModel() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLocator.init(this)
        enableEdgeToEdge()
        mineViewModel.attach(this)

        // Post-notification permission is requested lazily when the user
        // starts mining (API 33+); foreground service works without it but
        // the live notification is hidden until granted.
        setContent {
            NoidTheme {
                NoidNavHost(
                    startMining = { pref ->
                        requestNotificationPermission()
                        mineViewModel.startMining(this, pref)
                    },
                    pauseMining = mineViewModel::pause,
                    resumeMining = mineViewModel::resume,
                    stopMining = { mineViewModel.stopMining(this) },
                )
            }
        }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }
}
