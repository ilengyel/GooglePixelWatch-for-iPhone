package dev.toastmod.ancs.presentation

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import dev.toastmod.ancs.R
import dev.toastmod.ancs.presentation.theme.ANCSTheme

@android.annotation.SuppressLint("MissingPermission")
@Composable
fun PhoneConnectionListScreen(
    devices: List<BluetoothDevice>,
    selectedAddress: String?,
    onSelect: (BluetoothDevice) -> Unit,
    onInfo: (BluetoothDevice) -> Unit,
    onBack: () -> Unit
) {
    ANCSTheme {
        AppScaffold {
            ScreenScaffold { contentPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(contentPadding)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .selectableGroup(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.choose_iphone), style = MaterialTheme.typography.titleMedium)
                    devices.forEach { device ->
                        val selected = device.address == selectedAddress
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .selectable(selected = selected, role = Role.RadioButton,
                                        onClick = { onSelect(device) })
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    if (selected) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        device.alias ?: device.name ?: stringResource(R.string.unknown_phone),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text("...${device.address.takeLast(8)}",
                                        style = MaterialTheme.typography.bodySmall)
                                    if (selected) Text(stringResource(R.string.selected_phone),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            IconButton(onClick = { onInfo(device) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.Info,
                                    contentDescription = stringResource(R.string.device_connection_info, device.address))
                            }
                        }
                    }
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                }
            }
        }
    }
}
