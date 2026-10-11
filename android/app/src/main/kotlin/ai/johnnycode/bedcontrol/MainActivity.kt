package ai.johnnycode.bedcontrol

import ai.johnnycode.bedcontrol.control.BedController
import ai.johnnycode.bedcontrol.ui.BedControlTheme
import ai.johnnycode.bedcontrol.ui.RootScreen
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

class MainActivity : ComponentActivity() {
    private val controller: BedController by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BedControlTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PermissionGate(onGranted = { controller.permissionsGranted() }) {
                        LifecycleHooks(controller)
                        RootScreen(controller)
                    }
                }
            }
        }
    }
}

/** The runtime permissions BLE needs on this Android version. */
fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun hasPermissions(context: Context) =
    requiredPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** Android 10/11 only deliver BLE scan results while Location is switched on. */
fun locationOffBlocksScanning(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
    val manager = context.getSystemService(LocationManager::class.java) ?: return false
    return !manager.isLocationEnabled
}

@Composable
private fun PermissionGate(onGranted: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPermissions(context)) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
        denied = !granted
    }
    LaunchedEffect(granted) { if (granted) onGranted() else launcher.launch(requiredPermissions()) }
    if (granted) {
        content()
        return
    }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Bluetooth access needed", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                "Bed Control talks to your bed over Bluetooth. Allow \"Nearby devices\" so it can find and control the bed. It never uses your location."
            else
                "Bed Control talks to your bed over Bluetooth. On this Android version, finding Bluetooth devices requires the Location permission. The app does not use your location.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { launcher.launch(requiredPermissions()) }) { Text("Allow") }
        if (denied) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("Open app settings") }
        }
    }
}

@Composable
private fun LifecycleHooks(controller: BedController) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Never leave a held movement or a favorite running when the app leaves the foreground.
                Lifecycle.Event.ON_STOP -> controller.onBackground()
                Lifecycle.Event.ON_START -> {
                    controller.ble.refreshAdapterState()
                    controller.refresh()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
