package dev.toastmod.ancs.presentation

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
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
    onForget: () -> Unit
) {
    val context = LocalContext.current
    val media by mediaState.collectAsState()
    var bondState by remember(device) { mutableStateOf(BluetoothDevice.BOND_NONE) }
    var bluetoothEnabled by remember { mutableStateOf(false) }
    var deviceName by remember(device) { mutableStateOf("") }
    var permissionGranted by remember { mutableStateOf(true) }
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
            val listState = rememberTransformingLazyColumnState()
            val transformationSpec = rememberTransformationSpec()
            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
                    item {
                        ListHeader(
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec)
                        ) {
                            Text(
                                stringResource(R.string.connection_info),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .transformedHeight(this, transformationSpec),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                deviceName.ifBlank { stringResource(R.string.unknown_phone) },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                device.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item {
                        ListSubHeader(
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                            label = {
                                Text(stringResource(R.string.diagnostic_connection_section))
                            }
                        )
                    }
                    item {
                        DiagnosticRow(
                            label = stringResource(R.string.diagnostic_bond),
                            value = stringResource(
                                when (bondState) {
                                    BluetoothDevice.BOND_BONDED -> R.string.bond_paired
                                    BluetoothDevice.BOND_BONDING -> R.string.bond_pairing
                                    else -> R.string.bond_not_paired
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                        )
                    }
                    item {
                        DiagnosticRow(
                            label = stringResource(R.string.diagnostic_bluetooth),
                            value = stringResource(if (bluetoothEnabled) R.string.enabled else R.string.disabled),
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                        )
                    }
                    item {
                        DiagnosticRow(
                            label = stringResource(R.string.diagnostic_iphone_service),
                            value = stringResource(
                                when (connectionState) {
                                    PhoneConnectionState.CONNECTED -> R.string.iphone_connected
                                    PhoneConnectionState.CONNECTING -> R.string.iphone_connecting
                                    PhoneConnectionState.DISCONNECTED -> R.string.iphone_disconnected
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                        )
                    }
                    if (status.isNotBlank()) {
                        item {
                            DiagnosticRow(
                                label = stringResource(R.string.diagnostic_status),
                                value = status,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .transformedHeight(this, transformationSpec)
                            )
                        }
                    }
                    if (!selected) {
                        item {
                            Text(
                                stringResource(R.string.select_phone_to_forget),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .transformedHeight(this, transformationSpec),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!permissionGranted) {
                        item {
                            Text(
                                stringResource(R.string.forget_permission_denied),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .transformedHeight(this, transformationSpec),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    item {
                        ListSubHeader(
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                            label = {
                                Text(stringResource(R.string.diagnostic_media_section))
                            }
                        )
                    }
                    item {
                        DiagnosticRow(
                            label = stringResource(R.string.diagnostic_media),
                            value = stringResource(if (media.available) R.string.available else R.string.unavailable),
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                        )
                    }
                    item {
                        DiagnosticRow(
                            label = stringResource(R.string.diagnostic_commands),
                            value = media.supportedCommands.size.toString(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                        )
                    }
                    if (message.isNotBlank()) {
                        item {
                            Text(
                                message,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .transformedHeight(this, transformationSpec),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = onForget,
                            enabled = selected && !forgetting && permissionGranted && bluetoothEnabled &&
                                message != stringResource(R.string.phone_forgotten),
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            transformation = SurfaceTransformation(transformationSpec)
                        ) {
                            Text(stringResource(if (forgetting) R.string.forgetting_phone else R.string.forget_phone))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
