package dev.toastmod.ancs.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import dev.toastmod.ancs.R
import dev.toastmod.ancs.presentation.theme.ANCSTheme

@Composable
fun AppFilterScreen(
    apps: List<NotificationApp>,
    connected: Boolean,
    onEnabledChange: (String, Boolean) -> Unit
) {
    ANCSTheme {
        AppScaffold {
            val listState = rememberTransformingLazyColumnState()
            ScreenScaffold(scrollState = listState) { padding ->
                TransformingLazyColumn(contentPadding = padding, state = listState) {
                    item { ListHeader { Text(stringResource(R.string.notification_apps)) } }
                    if (!connected) {
                        item { Text(stringResource(R.string.apps_disconnected)) }
                    } else if (apps.isEmpty()) {
                        item { Text(stringResource(R.string.apps_empty)) }
                    }
                    items(apps.size, key = { apps[it].identifier }) { index ->
                        val app = apps[index]
                        SwitchButton(
                            checked = app.enabled,
                            onCheckedChange = { onEnabledChange(app.identifier, it) },
                            enabled = connected,
                            label = { Text(app.displayName) },
                            secondaryLabel = { Text(app.identifier) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
