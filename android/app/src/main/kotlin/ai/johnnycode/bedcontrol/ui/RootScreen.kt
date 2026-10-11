package ai.johnnycode.bedcontrol.ui

import ai.johnnycode.bedcontrol.control.BedController
import ai.johnnycode.bedkit.BedSide
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

sealed class Screen {
    data object Main : Screen()
    data object Scan : Screen()
    data object Diagnostics : Screen()
    data object Settings : Screen()
    data class Favorites(val side: BedSide) : Screen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(controller: BedController) {
    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    if (screen != Screen.Main) BackHandler { screen = Screen.Main }
    val noBed = controller.savedBed == null
    val title = when (val s = screen) {
        Screen.Main -> if (noBed) "Find your bed" else "Bed Control"
        Screen.Scan -> "Choose a bed"
        Screen.Diagnostics -> "Diagnostics"
        Screen.Settings -> "Settings"
        is Screen.Favorites -> "${s.side.title} favorites"
    }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (screen != Screen.Main) {
                        IconButton(onClick = { screen = Screen.Main }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    } else {
                        ConnectionPill(controller, Modifier)
                    }
                },
                actions = {
                    if (screen == Screen.Main) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Diagnostics") }, onClick = { menu = false; screen = Screen.Diagnostics })
                                DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; screen = Screen.Settings })
                                if (!noBed) {
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("Choose a different bed") }, onClick = { menu = false; screen = Screen.Scan })
                                    if (controller.isReady) {
                                        DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; controller.disconnect() })
                                    } else {
                                        DropdownMenuItem(text = { Text("Connect") }, onClick = { menu = false; controller.retry() })
                                    }
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (val s = screen) {
            Screen.Main -> if (noBed) ScanScreen(controller, padding, onChosen = {}) else ControlScreen(controller, padding, onFavorites = { screen = Screen.Favorites(it) })
            Screen.Scan -> ScanScreen(controller, padding, onChosen = { screen = Screen.Main })
            Screen.Diagnostics -> DiagnosticsScreen(controller, padding)
            Screen.Settings -> SettingsScreen(controller, padding, onForgotten = { screen = Screen.Main })
            is Screen.Favorites -> FavoritesScreen(controller, s.side, padding, onApplied = { screen = Screen.Main })
        }
    }
}

/** Compact connection status shown in the app bar. */
@Composable
fun ConnectionPill(controller: BedController, modifier: Modifier) {
    val (color, text) = when (controller.phase) {
        BedController.Phase.Ready -> Color(0xFF2E7D32) to (controller.protocolKind?.displayName ?: "Connected")
        is BedController.Phase.Searching -> Warning to "Searching"
        is BedController.Phase.Connecting -> Warning to "Connecting"
        is BedController.Phase.Failed -> StopRed to "Offline"
        is BedController.Phase.BluetoothUnavailable -> StopRed to "No Bluetooth"
        BedController.Phase.NoBed -> Color.Gray to "No bed"
    }
    Row(
        modifier.semantics { contentDescription = "Connection: $text" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp))
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}
