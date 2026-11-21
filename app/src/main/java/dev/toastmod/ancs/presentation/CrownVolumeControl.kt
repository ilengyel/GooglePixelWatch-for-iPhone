package dev.toastmod.ancs.presentation

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import dev.toastmod.ancs.R

internal class CrownVolumeInput {
    private var nextCommandAt = 0L

    fun command(delta: Float, now: Long, supported: Set<Byte>): Byte? {
        require(delta.isFinite()) { "Invalid rotary delta" }
        val command = when {
            delta > 0 -> AMSCommand.VOLUME_UP
            delta < 0 -> AMSCommand.VOLUME_DOWN
            else -> return null
        }
        if (command !in supported || now < nextCommandAt) return null
        nextCommandAt = now + 200
        return command
    }

    fun reset() {
        nextCommandAt = 0
    }
}

@Composable
internal fun CrownVolumeControl(
    state: AMSState,
    onCommand: (Byte) -> Unit,
    onEnd: () -> Unit,
    content: @Composable (Modifier) -> Unit
) {
    var active by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val input = remember { CrownVolumeInput() }
    val currentCommand by rememberUpdatedState(onCommand)
    val currentEnd by rememberUpdatedState(onEnd)
    val enabled = state.available && (AMSCommand.VOLUME_UP in state.supportedCommands ||
        AMSCommand.VOLUME_DOWN in state.supportedCommands)

    fun stop() {
        val wasActive = active
        active = false
        input.reset()
        currentEnd()
        if (wasActive) focusManager.clearFocus()
    }

    LifecycleResumeEffect(lifecycle) {
        onPauseOrDispose { stop() }
    }
    LaunchedEffect(active, enabled) {
        if (active) {
            if (enabled) requester.requestFocus() else stop()
        }
    }
    content(Modifier
        .onRotaryScrollEvent { event ->
            if (!active || !focused || !enabled ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                false
            } else {
                if (event.verticalScrollPixels.isFinite()) {
                    input.command(event.verticalScrollPixels, SystemClock.uptimeMillis(),
                        state.supportedCommands)?.let(currentCommand)
                } else {
                    Log.w("AMS", "Ignoring invalid crown volume input")
                }
                true
            }
        }
        .onFocusChanged {
            val lostFocus = focused && !it.hasFocus
            focused = it.hasFocus
            if (lostFocus && active) stop()
        }
        .focusRequester(requester)
        .focusable(enabled = active)
    )
    TextButton(
        enabled = enabled,
        onClick = { if (active) stop() else active = true }
    ) {
        Text(stringResource(if (active) R.string.crown_volume_on else R.string.crown_volume_off))
    }
}
