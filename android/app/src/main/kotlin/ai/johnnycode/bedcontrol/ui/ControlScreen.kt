package ai.johnnycode.bedcontrol.ui

import ai.johnnycode.bedcontrol.control.BedController
import ai.johnnycode.bedkit.BedSide
import ai.johnnycode.bedkit.MoveDirection
import ai.johnnycode.bedkit.SleepNumber
import ai.johnnycode.bedkit.driver.PresetOption
import ai.johnnycode.bedkit.mcr.FoundationAxis
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

@Composable
fun ControlScreen(controller: BedController, padding: PaddingValues, onFavorites: (BedSide) -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding)) {
        Column(
            Modifier.weight(1f)
                // A scroll must not steal the finger from a held movement button.
                .verticalScroll(rememberScrollState(), enabled = controller.activeHold == null)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(controller)
            controller.banner?.let { Banner(it) { controller.banner = null } }
            val enabled = controller.isReady
            Box(Modifier.alpha(if (enabled) 1f else 0.55f)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SidePanel(controller, BedSide.LEFT, Modifier.weight(1f), onFavorites)
                        SidePanel(controller, BedSide.RIGHT, Modifier.weight(1f), onFavorites)
                    }
                    FootPanel(controller)
                    if (controller.hasUnderBedLight) LightRow(controller)
                }
            }
        }
    }
}

/** STOP (always reachable) plus the running favorite's progress and Cancel; shown on every screen. */
@Composable
fun SafetyBar(controller: BedController) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            controller.applyingFavorite?.let { FavoriteProgressCard(it, onCancel = controller::cancelFavorite) }
            Button(
                onClick = controller::stopEverything,
                enabled = controller.isReady,
                colors = ButtonDefaults.buttonColors(containerColor = StopRed, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .semantics { contentDescription = "STOP. Stops all bed movement and any firmness adjustment" },
            ) {
                Text("STOP", fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun CardBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Box(Modifier.padding(12.dp)) { content() }
    }
}

@Composable
private fun StatusCard(controller: BedController) {
    val context = LocalContext.current
    val phase = controller.phase
    val isError = phase is BedController.Phase.Failed || phase is BedController.Phase.BluetoothUnavailable
    val detail = when (phase) {
        BedController.Phase.Ready -> controller.lastUpdated?.let {
            "${controller.protocolKind?.displayName ?: ""} · updated ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it))}"
        } ?: (controller.protocolKind?.displayName ?: "Connected")
        is BedController.Phase.Searching -> "Looking for ${phase.name}… (keeps trying until the bed is in range)"
        is BedController.Phase.Connecting -> phase.step
        is BedController.Phase.Failed -> phase.message
        is BedController.Phase.BluetoothUnavailable -> phase.message
        BedController.Phase.NoBed -> "No bed selected"
    }
    CardBox(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(controller.savedBed?.name ?: "Bed", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = if (isError) StopRed else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            when (phase) {
                is BedController.Phase.Searching, is BedController.Phase.Connecting -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                is BedController.Phase.Failed -> Button(onClick = controller::retry) { Text("Retry") }
                is BedController.Phase.BluetoothUnavailable -> Button(onClick = {
                    runCatching { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                }) { Text("Turn on") }
                else -> if (controller.moving) Text("Moving", color = Warning, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun Banner(text: String, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0x33FFC107)), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, null, tint = Color(0xFFFFA000), modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 2.dp))
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss") }
        }
    }
}

@Composable
private fun FavoriteProgressCard(run: BedController.FavoriteRun, onCancel: () -> Unit) {
    CardBox(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Applying \"${run.favorite.name}\" (${run.side.title})", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (run.stepIndex > 0) "Step ${run.stepIndex} of ${run.stepCount}: ${run.status}" else run.status,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = onCancel, enabled = !run.stopping) { Text(if (run.stopping) "Stopping…" else "Cancel") }
            }
            LinearProgressIndicator(
                progress = { (run.stepIndex.coerceAtLeast(1) - 1).toFloat() / run.stepCount + 0.5f / run.stepCount },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SidePanel(controller: BedController, side: BedSide, modifier: Modifier, onFavorites: (BedSide) -> Unit) {
    val enabled = controller.isReady
    CardBox(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(side.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                controller.activePreset[side]?.let {
                    Text(
                        it.title, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            FirmnessControl(controller, side)
            HorizontalDivider()
            Row {
                Text("Head", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(controller.head[side]?.let { "$it%" } ?: "–", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PositionBar(controller.head[side])
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HoldButton(controller, FoundationAxis.HEAD, side, MoveDirection.UP, Modifier.weight(1f))
                HoldButton(controller, FoundationAxis.HEAD, side, MoveDirection.DOWN, Modifier.weight(1f))
            }
            HorizontalDivider()
            controller.presetOptions.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { option -> PresetButton(controller, option, side, enabled, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            OutlinedButton(
                onClick = { onFavorites(side) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${side.title} side saved favorites" },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text("My favorites (${controller.favorites[side].size})", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PresetButton(controller: BedController, option: PresetOption, side: BedSide, enabled: Boolean, modifier: Modifier) {
    val active = option is PresetOption.Preset && controller.activePreset[side] == option.preset
    val label = "${option.accessibilityName}, ${side.title} side"
    if (active) {
        Button(onClick = { controller.activate(option, side) }, enabled = enabled, modifier = modifier.semantics { contentDescription = label },
            contentPadding = PaddingValues(2.dp)) { Text(option.title, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
    } else {
        FilledTonalButton(onClick = { controller.activate(option, side) }, enabled = enabled, modifier = modifier.semantics { contentDescription = label },
            contentPadding = PaddingValues(2.dp)) { Text(option.title, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
    }
}

@Composable
private fun FirmnessControl(controller: BedController, side: BedSide) {
    var draft by rememberSaveable(side) { mutableStateOf<Int?>(null) }
    val current = controller.firmness[side]
    val shown = draft ?: controller.requestedFirmness[side] ?: current ?: 50
    val applying = controller.applyingFavorite != null
    val status = when {
        controller.queuedFirmness[side] != null -> "Queued ${controller.queuedFirmness[side]}: waits for the pump"
        controller.adjustingSide == side -> controller.requestedFirmness[side]?.let { "Adjusting to $it…" } ?: "Adjusting…"
        controller.pumpBusy && controller.adjustingSide == null -> "Pump busy"
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Sleep Number", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(current?.toString() ?: "–", fontSize = 36.sp, fontWeight = FontWeight.Bold)
            status?.let { Text(" $it", style = MaterialTheme.typography.labelSmall, color = Warning, modifier = Modifier.padding(bottom = 6.dp)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StepButton("−", "Softer", shown > SleepNumber.range.first) { draft = SleepNumber.normalize(shown - SleepNumber.STEP) }
            Text("$shown", fontWeight = if (draft == null) FontWeight.Normal else FontWeight.Bold, modifier = Modifier.width(34.dp), maxLines = 1)
            StepButton("+", "Firmer", shown < SleepNumber.range.last) { draft = SleepNumber.normalize(shown + SleepNumber.STEP) }
        }
        val pending = draft
        if (pending != null && pending != current) {
            Button(
                onClick = {
                    controller.setFirmness(pending, side)
                    draft = null
                },
                // Disabled while a favorite runs: it sets the firmness itself.
                enabled = controller.isReady && !applying && controller.activeHold == null,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) { Text("Set $pending", style = MaterialTheme.typography.labelMedium) }
        } else if (controller.queuedFirmness[side] != null) {
            TextButton(onClick = { controller.cancelQueuedFirmness(side) }) { Text("Cancel queued", color = StopRed) }
        }
    }
}

@Composable
private fun StepButton(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick, enabled = enabled, contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(width = 40.dp, height = 32.dp).semantics { contentDescription = label },
    ) { Text(symbol, fontWeight = FontWeight.Bold) }
}

@Composable
private fun FootPanel(controller: BedController) {
    val footSide = controller.footSide
    CardBox(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Foot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("  shared", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(controller.foot[footSide]?.let { "$it%" } ?: "–", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PositionBar(controller.foot[footSide])
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                BedSide.entries.forEachIndexed { index, side ->
                    SegmentedButton(
                        selected = side == footSide,
                        onClick = { controller.updateFootSide(side) },
                        enabled = controller.activeHold == null,
                        shape = SegmentedButtonDefaults.itemShape(index, BedSide.entries.size),
                    ) { Text("As ${side.title}") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HoldButton(controller, FoundationAxis.FOOT, footSide, MoveDirection.UP, Modifier.weight(1f))
                HoldButton(controller, FoundationAxis.FOOT, footSide, MoveDirection.DOWN, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LightRow(controller: BedController) {
    val on = controller.lightOn == true
    CardBox(Modifier.fillMaxWidth().clickable(enabled = controller.isReady, role = Role.Switch) { controller.toggleLight() }
        .semantics { contentDescription = "Under-bed light, ${if (on) "on" else "off"}" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(if (on) Color(0xFFFFC107) else Color.Gray))
            Spacer(Modifier.width(10.dp))
            Text("Under-bed light", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(controller.lightOn?.let { if (it) "On" else "Off" } ?: "Unknown", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PositionBar(value: Int?) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth((value ?: 0).coerceIn(0, 100) / 100f).height(6.dp).background(MaterialTheme.colorScheme.primary))
    }
}

/**
 * Press-and-hold movement button. Movement runs only while a finger is down: lifting the finger, the
 * gesture being cancelled (e.g. by the system or a scroll), or the button leaving the screen all
 * release it.
 */
@Composable
private fun HoldButton(controller: BedController, axis: FoundationAxis, side: BedSide, direction: MoveDirection, modifier: Modifier) {
    val key = BedController.HoldKey(axis, side, direction)
    val active = controller.activeHold == key
    val otherActive = controller.activeHold != null && !active
    val enabled = controller.isReady && controller.applyingFavorite == null
    val haptics = LocalHapticFeedback.current
    val currentController by rememberUpdatedState(controller)
    var pressing by remember { mutableStateOf(false) }
    DisposableEffect(key) {
        onDispose { if (pressing) currentController.endHold() }
    }
    val label = "${if (axis == FoundationAxis.HEAD) "${side.title} head" else "Foot"} ${if (direction == MoveDirection.UP) "up" else "down"}"
    Box(
        modifier
            .heightIn(min = 48.dp)
            .alpha(if (otherActive || !enabled) 0.4f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .pointerInput(enabled, key) {
                detectTapGestures(onPress = {
                    if (!enabled || currentController.activeHold != null) return@detectTapGestures
                    pressing = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentController.beginHold(axis, side, direction)
                    try {
                        tryAwaitRelease()
                    } finally {
                        pressing = false
                        currentController.endHold()
                    }
                })
            }
            .semantics {
                contentDescription = "$label. Press and hold to move"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (direction == MoveDirection.UP) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
    }
}
