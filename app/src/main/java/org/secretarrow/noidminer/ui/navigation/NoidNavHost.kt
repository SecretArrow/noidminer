package org.secretarrow.noidminer.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Scanner
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.secretarrow.noidminer.ui.screen.DeviceScreen
import org.secretarrow.noidminer.ui.screen.LogScreen
import org.secretarrow.noidminer.ui.screen.MineScreen
import org.secretarrow.noidminer.ui.screen.PoolsScreen
import org.secretarrow.noidminer.ui.screen.SettingsScreen
import org.secretarrow.noidminer.ui.screen.WalletScreen

/** App destinations — five bottom tabs plus a settings route. */
sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Mine : Dest("mine", "Mine", Icons.Filled.Bolt)
    data object Pools : Dest("pools", "Pools", Icons.Filled.Scanner)
    data object Wallet : Dest("wallet", "Wallet", Icons.Filled.Savings)
    data object Device : Dest("device", "Device", Icons.Filled.Memory)
    data object Log : Dest("log", "Log", Icons.Filled.Terminal)
    data object Settings : Dest("settings", "Settings", Icons.Filled.Memory)

    companion object {
        val tabs = listOf(Mine, Pools, Wallet, Device, Log)
    }
}

/** Root scaffold: bottom navigation + NavHost. */
@Composable
fun NoidNavHost(startMining: () -> Unit, pauseMining: () -> Unit, resumeMining: () -> Unit, stopMining: () -> Unit) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Dest.Mine.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Dest.tabs.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(Dest.Mine.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Mine.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Dest.Mine.route) {
                MineScreen(
                    onStart = startMining,
                    onPause = pauseMining,
                    onResume = resumeMining,
                    onStop = stopMining,
                )
            }
            composable(Dest.Pools.route) { PoolsScreen() }
            composable(Dest.Wallet.route) { WalletScreen() }
            composable(Dest.Device.route) { DeviceScreen() }
            composable(Dest.Log.route) { LogScreen() }
            composable(Dest.Settings.route) { SettingsScreen() }
        }
    }
}
