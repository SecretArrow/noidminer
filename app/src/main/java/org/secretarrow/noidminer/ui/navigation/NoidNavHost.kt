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

/** Bottom tabs — plain enum, no sealed-class indirection. */
enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Mine("mine", "Mine", Icons.Filled.Bolt),
    Pools("pools", "Pools", Icons.Filled.Scanner),
    Wallet("wallet", "Wallet", Icons.Filled.Savings),
    Device("device", "Device", Icons.Filled.Memory),
    Log("log", "Log", Icons.Filled.Terminal),
}

private const val SETTINGS_ROUTE = "settings"

/** Root scaffold: bottom navigation + NavHost. */
@Composable
fun NoidNavHost(
    startMining: () -> Unit,
    pauseMining: () -> Unit,
    resumeMining: () -> Unit,
    stopMining: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Tab.Mine.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    val route = tab.route
                    val label = tab.label
                    val icon = tab.icon
                    NavigationBarItem(
                        selected = currentRoute == route,
                        onClick = {
                            navController.navigate(route) {
                                popUpTo(Tab.Mine.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Mine.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.Mine.route) {
                MineScreen(
                    onStart = startMining,
                    onPause = pauseMining,
                    onResume = resumeMining,
                    onStop = stopMining,
                )
            }
            composable(Tab.Pools.route) { PoolsScreen() }
            composable(Tab.Wallet.route) { WalletScreen() }
            composable(Tab.Device.route) { DeviceScreen() }
            composable(Tab.Log.route) { LogScreen() }
            composable(SETTINGS_ROUTE) { SettingsScreen() }
        }
    }
}
