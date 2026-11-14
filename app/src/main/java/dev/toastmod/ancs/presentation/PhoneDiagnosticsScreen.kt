package dev.toastmod.ancs.presentation

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import dev.toastmod.ancs.R
import dev.toastmod.ancs.presentation.theme.ANCSTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

@android.annotation.SuppressLint("MissingPermission")
@Composable
fun PhoneDiagnosticsScreen(
    device: BluetoothDevice,
    connectionState: PhoneConnectionState,
    status: String,
    mediaState: StateFlow<AMSState>,
    selected: Boolean,
    forgetting: Boolean,
    message: String,
    onForget: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val media by mediaState.collectAsState()
    var bondState by remember(device) { mutableStateOf(BluetoothDevice.BOND_NONE) }
    var bluetoothEnabled by remember { mutableStateOf(false) }
    var deviceName by remember(device) { mutableStateOf("") }
    var permissionGranted by remember { mutableStateOf(true) }
    val unpairApiPresent = remember {
        try {
            BluetoothDevice::class.java.getMethod("removeBond")
            true
        } catch (error: NoSuchMethodException) {
            Log.w("ANCS", "Individual unpair API is not exposed", error)
            false
        } catch (error: SecurityException) {
            Log.w("ANCS", "Individual unpair API lookup is blocked", error)
            false
        }
    }
    LaunchedEffect(device) {
        while (true) {
            permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            if (permissionGranted) {
                try {
                    bondState = device.bondState
                    deviceName = device.alias ?: device.name.orEmpty()
                    bluetoothEnabled =
                        context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
                } catch (error: SecurityException) {
                    Log.e("ANCS", "Bluetooth permission revoked while reading diagnostics", error)
                    permissionGranted = false
                }
            }
            delay(1_000)
        }
    }
    ANCSTheme {
        AppScaffold {
            ScreenScaffold { contentPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .requestFocusOnHierarchyActive()
                        .rotaryScrollable(
                            behavior = RotaryScrollableDefaults.behavior(scrollState),
                            focusRequester = focusRequester
                        )
                        .verticalScroll(scrollState)
                        .padding(contentPadding)
                        .padding(horizontal = 28.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.connection_info), style = MaterialTheme.typography.titleMedium)
                    Text(deviceName.ifBlank { stringResource(R.string.unknown_phone) })
                    Text(device.address, style = MaterialTheme.typography.bodySmall)
                    if (!selected) Text(stringResource(R.string.select_phone_to_forget))
                    if (!permissionGranted) Text(stringResource(R.string.forget_permission_denied))
                    Text(stringResource(R.string.diagnostic_bond, stringResource(
                        when (bondState) {
                            BluetoothDevice.BOND_BONDED -> R.string.bond_paired
                            BluetoothDevice.BOND_BONDING -> R.string.bond_pairing
                            else -> R.string.bond_not_paired
                        }
                    )))
                    Text(stringResource(R.string.diagnostic_bluetooth, stringResource(
                        if (bluetoothEnabled) R.string.enabled else R.string.disabled
                    )))
                    Text(stringResource(when (connectionState) {
                        PhoneConnectionState.CONNECTED -> R.string.iphone_connected
                        PhoneConnectionState.CONNECTING -> R.string.iphone_connecting
                        PhoneConnectionState.DISCONNECTED -> R.string.iphone_disconnected
                    }))
                    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.diagnostic_media, stringResource(
                        if (media.available) R.string.available else R.string.unavailable
                    )))
                    Text(stringResource(R.string.diagnostic_commands, media.supportedCommands.size))
                    Text(stringResource(
                        if (unpairApiPresent) R.string.unpair_api_present else R.string.unpair_api_unavailable
                    ), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.unpair_api_warning), style = MaterialTheme.typography.bodySmall)
                    if (message.isNotBlank()) Text(message)
                    TextButton(
                        onClick = onForget,
                        enabled = selected && !forgetting && permissionGranted && bluetoothEnabled &&
                            message != stringResource(R.string.phone_forgotten)
                    ) {
                        Text(stringResource(if (forgetting) R.string.forgetting_phone else R.string.forget_phone))
                    }
                    TextButton(onClick = onBack, enabled = !forgetting) {
                        Text(stringResource(R.string.back))
                    }
                }
            }
        }
    }
}
