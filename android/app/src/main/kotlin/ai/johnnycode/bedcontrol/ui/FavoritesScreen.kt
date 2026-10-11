package ai.johnnycode.bedcontrol.ui

import ai.johnnycode.bedcontrol.control.BedController
import ai.johnnycode.bedkit.BedSide
import ai.johnnycode.bedkit.SleepNumber
import ai.johnnycode.bedkit.favorites.Favorite
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One sleeper's saved favorites: apply, save from the bed's current state, edit, rename, delete, reorder. */
@Composable
fun FavoritesScreen(controller: BedController, side: BedSide, padding: PaddingValues, onApplied: () -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Favorite?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Favorite?>(null) }
    val items = controller.favorites[side]
    val applyBlocker = controller.applyBlocker()

    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            controller.banner?.let { Banner(it) { controller.banner = null } }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            capturing = true
                            editing = controller.captureFavorite(side)
                            capturing = false
                        }
                    },
                    enabled = !capturing && controller.isReady,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (capturing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save current position as favorite")
                }
                Text(
                    "Reads the ${side.title.lowercase()} head, the shared foot and the ${side.title.lowercase()} Sleep Number from the bed. You can adjust them before saving.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (items.isEmpty()) {
            item { Text("No favorites yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
        }
        itemsIndexed(items, key = { _, f -> f.id }) { index, favorite ->
            FavoriteRow(
                favorite = favorite,
                canApply = applyBlocker == null,
                canMoveUp = index > 0,
                canMoveDown = index < items.size - 1,
                onApply = {
                    controller.applyFavorite(favorite, side)
                    if (controller.applyingFavorite != null) onApplied()
                },
                onEdit = { editing = favorite },
                onMoveUp = { controller.moveFavoriteUp(favorite.id, side) },
                onMoveDown = { controller.moveFavoriteDown(favorite.id, side) },
                onDelete = { confirmDelete = favorite },
            )
        }
        item {
            Text(
                "Apply moves the ${side.title.lowercase()} head and the foot, then sets the Sleep Number. STOP cancels it at any point. " +
                    "The bed's own Favorite preset is separate." + (applyBlocker?.let { "\n\nApply is unavailable: $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    editing?.let { favorite ->
        val isNew = controller.favorites[side].none { it.id == favorite.id }
        FavoriteEditor(favorite, isNew, onDismiss = { editing = null }) { saved ->
            controller.saveFavorite(saved, side)
            editing = null
        }
    }
    confirmDelete?.let { favorite ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${favorite.name}\"?") },
            confirmButton = { TextButton(onClick = { controller.deleteFavorite(favorite.id, side); confirmDelete = null }) { Text("Delete", color = StopRed) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FavoriteRow(
    favorite: Favorite,
    canApply: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(favorite.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Head ${favorite.head}% · Foot ${favorite.foot}% · Sleep Number ${favorite.sleepNumber}", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onApply, enabled = canApply, modifier = Modifier.semantics { contentDescription = "Apply ${favorite.name}" }) { Text("Apply") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More actions for ${favorite.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit or rename") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Move up") }, enabled = canMoveUp, onClick = { menu = false; onMoveUp() })
                    DropdownMenuItem(text = { Text("Move down") }, enabled = canMoveDown, onClick = { menu = false; onMoveDown() })
                    DropdownMenuItem(text = { Text("Delete", color = StopRed) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** Name and values editor used for both new and existing favorites. */
@Composable
private fun FavoriteEditor(favorite: Favorite, isNew: Boolean, onDismiss: () -> Unit, onSave: (Favorite) -> Unit) {
    var name by remember { mutableStateOf(favorite.name) }
    var head by remember { mutableIntStateOf(favorite.head) }
    var foot by remember { mutableIntStateOf(favorite.foot) }
    var sleepNumber by remember { mutableIntStateOf(favorite.sleepNumber) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New favorite" else "Edit favorite") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Head $head%")
                Slider(value = head.toFloat(), onValueChange = { head = it.roundToInt() }, valueRange = 0f..100f,
                    modifier = Modifier.semantics { contentDescription = "Head position" })
                Text("Foot $foot%")
                Slider(value = foot.toFloat(), onValueChange = { foot = it.roundToInt() }, valueRange = 0f..100f,
                    modifier = Modifier.semantics { contentDescription = "Foot position" })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sleep Number", modifier = Modifier.weight(1f))
                    FilledTonalButton(
                        onClick = { sleepNumber = SleepNumber.normalize(sleepNumber - SleepNumber.STEP) },
                        enabled = sleepNumber > SleepNumber.range.first, contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(40.dp, 32.dp).semantics { contentDescription = "Softer" },
                    ) { Text("−") }
                    Text("$sleepNumber", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
                    FilledTonalButton(
                        onClick = { sleepNumber = SleepNumber.normalize(sleepNumber + SleepNumber.STEP) },
                        enabled = sleepNumber < SleepNumber.range.last, contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(40.dp, 32.dp).semantics { contentDescription = "Firmer" },
                    ) { Text("+") }
                }
                Spacer(Modifier.width(1.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(favorite.edited(name = name, head = head, foot = foot, sleepNumber = sleepNumber)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
