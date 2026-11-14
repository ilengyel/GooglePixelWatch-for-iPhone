package dev.toastmod.ancs.presentation

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SplitRadioButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import dev.toastmod.ancs.R
import dev.toastmod.ancs.presentation.theme.ANCSTheme

@android.annotation.SuppressLint("MissingPermission")
@Composable
fun PhoneConnectionListScreen(
    devices: List<BluetoothDevice>,
    selectedAddress: String?,
    onSelect: (BluetoothDevice) -> Unit,
    onInfo: (BluetoothDevice) -> Unit
) {
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
                                stringResource(R.string.choose_iphone),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    devices.forEach { device ->
                        val selected = device.address == selectedAddress
                        item(key = device.address) {
                            val name = device.alias ?: device.name ?: stringResource(R.string.unknown_phone)
                            SplitRadioButton(
                                selected = selected,
                                onSelectionClick = { onSelect(device) },
                                selectionContentDescription = stringResource(R.string.select_phone, name),
                                onContainerClick = { onInfo(device) },
                                containerClickLabel = stringResource(
                                    R.string.device_connection_info,
                                    name
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .transformedHeight(this, transformationSpec),
                                transformation = SurfaceTransformation(transformationSpec),
                                secondaryLabel = {
                                    Text(
                                        "...${device.address.takeLast(8)}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                label = {
                                    Text(
                                        name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
