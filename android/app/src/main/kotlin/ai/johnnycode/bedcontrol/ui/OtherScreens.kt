package ai.johnnycode.bedcontrol.ui

import ai.johnnycode.bedcontrol.ble.AdapterState
import ai.johnnycode.bedcontrol.ble.ScannedDevice
import ai.johnnycode.bedcontrol.control.BedController
import ai.johnnycode.bedcontrol.locationOffBlocksScanning
import ai.johnnycode.bedkit.FrameEvent
import ai.johnnycode.bedkit.hex
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// MARK: - Scan

@Composable
fun ScanScreen(controller: BedController, padding: PaddingValues, onChosen: () -> Unit) {
    val ble = controller.ble
    val context = LocalContext.current
    DisposableEffect(ble.adapterState) {
        if (ble.adapterState == AdapterState.ON) ble.startScan()
        onDispose { ble.stopScan() }
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Close the Sleep Number app on every phone nearby. The bed accepts only a limited number of Bluetooth connections. " +
                    "If the bed asks to pair, press the pair button on the pump or remote.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        (controller.phase as? BedController.Phase.BluetoothUnavailable)?.let { item { Text(it.message, color = StopRed) } }
        if (locationOffBlocksScanning(context)) {
            item {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Location is off. On this Android version, Bluetooth scanning only works with Location on (the app does not use your location).")
                        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Location settings") }
                    }
                }
            }
        }
        item { Text("Nearby", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
        val visible = ble.visibleDevices
        if (visible.isEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (ble.isScanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(if (ble.isScanning) "Looking for Sleep Number beds…" else "Not scanning", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(visible, key = { it.address }) { device ->
            DeviceRow(device) {
                ble.stopScan()
                controller.choose(device)
                onChosen()
            }
        }
        item {
            Text(
                "Older beds (MCR) advertise their Bluetooth address as the name, e.g. 64:db:a0:xx:xx:xx.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show all Bluetooth devices", modifier = Modifier.weight(1f))
                Switch(checked = ble.showAllDevices, onCheckedChange = { ble.showAllDevices = it })
            }
        }
        item {
            Button(onClick = { if (ble.isScanning) ble.stopScan() else ble.startScan() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (ble.isScanning) "Stop scanning" else "Scan again")
            }
        }
    }
}

@Composable
private fun DeviceRow(device: ScannedDevice, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, fontFamily = FontFamily.Monospace, maxLines = 1)
                if (device.name != device.address) Text(device.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                if (device.classification.reasons.isNotEmpty()) {
                    Text(device.classification.reasons.joinToString(" · ") { it.label }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                device.classification.likelyProtocol?.let { Text("Likely ${it.displayName}", style = MaterialTheme.typography.labelSmall) }
            }
            Text("${device.rssi} dBm", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// MARK: - Diagnostics

@Composable
fun DiagnosticsScreen(controller: BedController, padding: PaddingValues) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf(0) } // 0 all, 1 frames, 2 events
    val entries = controller.log.entries.filter {
        when (filter) {
            1 -> it.event.direction == FrameEvent.Direction.TX || it.event.direction == FrameEvent.Direction.RX
            2 -> it.event.direction == FrameEvent.Direction.INFO || it.event.direction == FrameEvent.Direction.ERROR
            else -> true
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.scrollToItem(entries.size - 1) }
    fun export() = controller.log.export(controller.diagnosticsHeader())

    Column(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            val rows = controller.diagnostics
            if (rows.isEmpty()) Text("Not connected", style = MaterialTheme.typography.bodySmall)
            rows.forEach { (k, v) ->
                Row {
                    Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            listOf("All", "Frames", "Events").forEachIndexed { index, label ->
                SegmentedButton(selected = filter == index, onClick = { filter = index }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(label) }
            }
        }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                items(entries, key = { it.id }) { entry ->
                    val color = when (entry.event.direction) {
                        FrameEvent.Direction.TX -> Color(0xFF1E88E5)
                        FrameEvent.Direction.RX -> Color(0xFF43A047)
                        FrameEvent.Direction.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                        FrameEvent.Direction.ERROR -> StopRed
                    }
                    Column(Modifier.padding(vertical = 3.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(entry.timeText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(entry.event.direction.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = color, fontWeight = FontWeight.Bold)
                            Text(entry.event.channel, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(entry.event.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        entry.event.bytes?.let { Text(it.hex(), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    HorizontalDivider()
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { controller.log.clear() }) { Text("Clear", color = StopRed) }
            TextButton(onClick = controller::refresh, enabled = controller.isReady) { Text("Read status") }
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Bed Control diagnostics", export()))
            }) { Text("Copy all") }
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Bed Control diagnostics")
                    putExtra(Intent.EXTRA_TEXT, export())
                }
                context.startActivity(Intent.createChooser(send, "Share diagnostics"))
            }) { Text("Share") }
        }
    }
}

// MARK: - Settings

@Composable
fun SettingsScreen(controller: BedController, padding: PaddingValues, onForgotten: () -> Unit) {
    var confirmForget by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sides", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().clickable { controller.updateSwapSides(!controller.swapSides) }, verticalAlignment = Alignment.CenterVertically) {
            Text("Swap left and right", modifier = Modifier.weight(1f))
            Switch(checked = controller.swapSides, onCheckedChange = { controller.updateSwapSides(it) })
        }
        Text(
            "Left and Right mean the sides as you lie in bed on your back. If pressing Left moves your partner's side, turn this on. " +
                "Reports disagree on how the bed numbers its sides, so check once with a small head movement.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Text("Bed", style = MaterialTheme.typography.titleSmall)
        Text("Name: ${controller.savedBed?.name ?: "None"}")
        Text("Address: ${controller.savedBed?.address ?: "–"}")
        Text("Protocol: ${(controller.protocolKind ?: controller.savedBed?.lastProtocol)?.displayName ?: "Unknown"}")
        if (controller.savedBed != null) {
            OutlinedButton(onClick = { confirmForget = true }) { Text("Forget this bed", color = StopRed) }
        }
        HorizontalDivider()
        Text(
            "Bed Control talks to the bed directly over Bluetooth. It cannot work while the Sleep Number app (or another phone) holds all of the bed's connections.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this bed?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    controller.forgetBed()
                    onForgotten()
                }) { Text("Forget", color = StopRed) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}
