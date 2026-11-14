package dev.toastmod.ancs.presentation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import dev.toastmod.ancs.R

@Composable
fun PhoneConnectionButton(
    connectionState: PhoneConnectionState,
    status: String,
    onChoosePhone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stateLabel = stringResource(
        when (connectionState) {
            PhoneConnectionState.CONNECTED -> R.string.iphone_connected
            PhoneConnectionState.CONNECTING -> R.string.iphone_connecting
            PhoneConnectionState.DISCONNECTED -> R.string.iphone_disconnected
        }
    )
    val tint = colorResource(
        when (connectionState) {
            PhoneConnectionState.CONNECTED -> R.color.phone_connected
            PhoneConnectionState.CONNECTING -> R.color.phone_connecting
            PhoneConnectionState.DISCONNECTED -> R.color.phone_disconnected
        }
    )
    IconButton(
        onClick = onChoosePhone,
        modifier = modifier
            .size(48.dp)
            .semantics {
                stateDescription = if (status.isBlank()) stateLabel else "$stateLabel. $status"
                if (connectionState == PhoneConnectionState.CONNECTING) {
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                }
            }
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_phone),
                contentDescription = stringResource(R.string.choose_iphone),
                tint = tint,
                modifier = Modifier.size(width = 24.dp, height = 32.dp)
            )
            when (connectionState) {
                PhoneConnectionState.CONNECTING -> {
                    val transition = rememberInfiniteTransition(label = "phone_connection")
                    val rotation by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1_200, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "connecting_ring"
                    )
                    Canvas(modifier = Modifier.size(40.dp)) {
                        val stroke = 2.dp.toPx()
                        drawArc(
                            color = tint,
                            startAngle = rotation - 90f,
                            sweepAngle = 240f,
                            useCenter = false,
                            topLeft = Offset(stroke / 2, stroke / 2),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke)
                        )
                    }
                }
                PhoneConnectionState.DISCONNECTED -> {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .background(MaterialTheme.colorScheme.background, CircleShape)
                            .size(16.dp)
                    )
                }
                PhoneConnectionState.CONNECTED -> Unit
            }
        }
    }
}
