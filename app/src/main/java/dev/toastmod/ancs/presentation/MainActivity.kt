package dev.toastmod.ancs.presentation

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.BasicSwipeToDismissBox
import androidx.lifecycle.lifecycleScope
import dev.toastmod.ancs.R
import kotlinx.coroutines.launch
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import dev.toastmod.ancs.presentation.theme.ANCSTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.Lifecycle
import java.util.UUID

val ANCS_SERVICE: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
val ANCS_NOTIFICAITON_SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
val ANCS_DATA_SOURCE: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
val ANCS_CONTROL_POINT: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")

enum class EventID(val b: Byte) {
    NotificationAdded(0),
    NotificationModified(1),
    NotificationRemoved(2),
    RESERVED(3);

    companion object {
        private val key = EventID::b
        private val map = entries.associateBy(key)
        fun fromByte(b: Byte): EventID {
            val bb = map[b]
            if (bb != null) {
                return bb
            } else {
                return RESERVED
            }
        }
    }
}

enum class CategoryID(val b: Byte) {
    Other(0),
    IncomingCall(1),
    MissedCall(2),
    Voicemail(3),
    Social(4),
    Schedule(5),
    Email(6),
    News(7),
    HealthAndFitness(8),
    BusinessAndFinance(9),
    Location(10),
    Entertainment(11),
    RESERVED(12);

    companion object {
        private val key = CategoryID::b
        private val map = entries.associateBy(key)
        fun fromByte(b: Byte): CategoryID {
            val bb = map[b]
            if (bb != null) {
                return bb
            } else {
                return RESERVED
            }
        }
    }
}

enum class EventFlag(val b: Byte) {
    Silent(0),
    Important(1),
    PreExisting(2),
    PositiveAction(3),
    NegativeAction(4),
    RESERVED(5);

    companion object {
        private val key = EventFlag::b
        private val map = entries.associateBy(key)
        fun fromByte(b: Byte): EventFlag {
            val bb = map[b]
            if (bb != null) {
                return bb
            } else {
                return RESERVED
            }
        }
    }
}

enum class CommandID(val b: Byte) {
    GetNotificationAttributes(0),
    GetAppAttributes(1),
    PerformNotificationAction(2),
    RESERVED(3);

    companion object {
        private val key = CommandID::b
        private val map = entries.associateBy(key)
        fun fromByte(b: Byte): CommandID {
            val bb = map[b]
            if (bb != null) {
                return bb
            } else {
                return RESERVED
            }
        }
    }
}

enum class NotificationAttributeID(val b: Byte) {
    AppIdentifier(0),
    Title(1),
    Subtitle(2),
    Message(3),
    MessageSize(4),
    Date(5),
    PositiveActionLabel(6),
    NegativeActionLabel(7),
    RESERVED(8);

    companion object {
        private val key = NotificationAttributeID::b
        private val map = entries.associateBy(key)
        fun fromByte(b: Byte): NotificationAttributeID {
            val bb = map[b]
            if (bb != null) {
                return bb
            } else {
                return RESERVED
            }
        }
    }
}

enum class ActionID(val b: Byte) {
    Positive(0),
    Negative(1),
    RESERVED(3);
    companion object {
        private val key = ActionID::b
        private val map = entries.associateBy(key)
        fun fromByte(b: Byte): ActionID {
            val bb = map[b]
            if (bb != null) {
                return bb
            } else {
                return RESERVED
            }
        }
    }
}

enum class AppAttributeID(val b: Byte) {
    DisplayName(0),
    RESERVED(1);

    companion object {
        private val key = AppAttributeID::b
        private val map = entries.associateBy(key)
        fun fromByte(b: Byte): AppAttributeID {
            val bb = map[b]
            if (bb != null) {
                return bb
            } else {
                return RESERVED
            }
        }
    }
}

val NOTIF_CHANNEL_ID = "com.apple.ancs"

class MainActivity : ComponentActivity() {
    private var gattService by mutableStateOf<ANCSAndroidService?>(null)
    private val disconnectedState = MutableStateFlow(AMSState())
    private val disconnectedConnectionState = MutableStateFlow(PhoneConnectionState.DISCONNECTED)
    private val notForgetting = MutableStateFlow(false)
    private val localStatus = MutableStateFlow("Select a paired iPhone")
    private var bound = false
    private var chooser: AlertDialog? = null
    private var diagnosticPhone by mutableStateOf<BluetoothDevice?>(null)
    private var showingApps by mutableStateOf(false)
    private val emptyApps = MutableStateFlow<List<NotificationApp>>(emptyList())
    private var phoneChoices by mutableStateOf<List<BluetoothDevice>?>(null)
    private var selectedPhoneAddress by mutableStateOf<String?>(null)
    private val forgetInProgress get() = gattService?.forgettingPhone?.value == true
    private var diagnosticMessage by mutableStateOf("")
    private val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
    // ComponentActivity does not use FragmentActivity's legacy permission handling.
    @android.annotation.SuppressLint("InvalidFragmentVersionForActivityResult")
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPermissions()) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startAndBind()
        } else {
            showMessage("Bluetooth and notification permissions are required")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            gattService = (binder as ANCSAndroidService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            gattService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val service = gattService
            val connectionStatus by (service?.status ?: localStatus).collectAsStateWithLifecycle()
            val phoneConnectionState by
                (service?.connectionState ?: disconnectedConnectionState).collectAsStateWithLifecycle()
            val forgetting by (service?.forgettingPhone ?: notForgetting).collectAsStateWithLifecycle()
            val phone = diagnosticPhone
            val choices = phoneChoices
            val apps by (service?.notificationApps ?: emptyApps).collectAsStateWithLifecycle()
            PhoneScreenBackNavigation(
                hasPreviousScreen = phone != null || choices != null || showingApps,
                navigationBlocked = forgetting,
                onBack = {
                    when {
                        showingApps -> showingApps = false
                        phone != null -> diagnosticPhone = null
                        else -> phoneChoices = null
                    }
                }
            ) {
                if (showingApps) {
                    AppFilterScreen(
                        apps = apps,
                        connected = phoneConnectionState == PhoneConnectionState.CONNECTED,
                        onEnabledChange = { identifier, enabled ->
                            try {
                                check(service != null) { "No active iPhone connection" }
                                service.setNotificationAppEnabled(identifier, enabled)
                            } catch (error: IllegalStateException) {
                                showMessage(error.message ?: "Could not update notification filter")
                            }
                        }
                    )
                } else if (phone != null) {
                    val isSelected = phone.address == selectedPhoneAddress
                    PhoneDiagnosticsScreen(
                        device = phone,
                        connectionState = if (isSelected) phoneConnectionState else PhoneConnectionState.DISCONNECTED,
                        status = if (isSelected) connectionStatus else getString(R.string.phone_not_selected),
                        mediaState = if (isSelected) service?.mediaState ?: disconnectedState else disconnectedState,
                        selected = isSelected,
                        forgetting = forgetting,
                        message = diagnosticMessage,
                        onForget = { confirmForget(phone) }
                    )
                } else if (choices != null) {
                    PhoneConnectionListScreen(
                        devices = choices,
                        selectedAddress = selectedPhoneAddress,
                        onSelect = { device ->
                            if (forgetInProgress) {
                                showMessage("Wait for the current forget request to finish")
                            } else {
                                getSharedPreferences(ANCSAndroidService.PREFERENCES, MODE_PRIVATE).edit()
                                    .putString(ANCSAndroidService.TARGET_ADDRESS, device.address).apply()
                                selectedPhoneAddress = device.address
                                phoneChoices = null
                                startAndBind()
                            }
                        },
                        onInfo = { device -> showConnectionInfo(device) }
                    )
                } else WearApp(
                    amsState = service?.mediaState ?: disconnectedState,
                    onTogglePlayPause = { sendAMSCommand(AMSCommand.TOGGLE_PLAY_PAUSE) },
                    onNextTrack = { sendAMSCommand(AMSCommand.NEXT_TRACK) },
                    onPreviousTrack = { sendAMSCommand(AMSCommand.PREVIOUS_TRACK) },
                    onCommand = { sendAMSCommand(it) },
                    onCrownVolume = { sendAMSCommand(it, fromCrown = true) },
                    onCrownVolumeEnd = { service?.cancelCrownVolume() },
                    onNotificationApps = { showingApps = true },
                    status = connectionStatus,
                    connectionState = phoneConnectionState,
                    onChoosePhone = {
                        if (hasPermissions()) choosePhone() else permissionRequest.launch(permissions)
                    }
                )
            }
        }
        if (!hasPermissions()) permissionRequest.launch(permissions)
    }

    override fun onStart() {
        super.onStart()
        if (hasPermissions()) startAndBind()
    }

    override fun onStop() {
        chooser?.dismiss()
        chooser = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        gattService = null
        super.onStop()
    }

    private fun hasPermissions() = permissions.all {
        ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun showMessage(message: String) {
        localStatus.value = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startAndBind() {
        if (!hasPermissions()) return
        val address = getSharedPreferences(ANCSAndroidService.PREFERENCES, MODE_PRIVATE)
            .getString(ANCSAndroidService.TARGET_ADDRESS, null)
        selectedPhoneAddress = address
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (address == null || adapter == null ||
            (adapter.isEnabled && adapter.bondedDevices.none { it.address == address })
        ) {
            choosePhone()
            return
        }
        val intent = Intent(this, ANCSAndroidService::class.java)
            .putExtra(ANCSAndroidService.TARGET_ADDRESS, address)
        startForegroundService(intent)
        if (!bound) {
            bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) showMessage("Could not bind to the iPhone connection service")
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun choosePhone() {
        if (!hasPermissions() || chooser != null || phoneChoices != null) return
        if (forgetInProgress) {
            showMessage("Wait for the current forget request to finish")
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) {
            showMessage("Enable Bluetooth in watch settings")
            return
        }
        val devices = adapter.bondedDevices.sortedBy { it.alias ?: it.name ?: it.address }
        if (devices.isEmpty()) {
            showMessage("Pair your iPhone in watch Bluetooth settings first")
            return
        }
        selectedPhoneAddress = getSharedPreferences(ANCSAndroidService.PREFERENCES, MODE_PRIVATE)
            .getString(ANCSAndroidService.TARGET_ADDRESS, null)
        phoneChoices = devices
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showConnectionInfo(device: BluetoothDevice) {
        if (!hasPermissions()) {
            permissionRequest.launch(permissions)
            return
        }
        diagnosticMessage = ""
        diagnosticPhone = device
    }

    private fun confirmForget(phone: BluetoothDevice) {
        if (chooser != null || forgetInProgress) return
        chooser = AlertDialog.Builder(this)
            .setTitle(R.string.forget_phone)
            .setMessage(getString(R.string.forget_phone_confirmation, phone.address))
            .setPositiveButton(R.string.forget_phone) { _, _ ->
                lifecycleScope.launch {
                    diagnosticMessage = ""
                    try {
                        if (!hasPermissions()) throw SecurityException("Bluetooth permission is required")
                        val service = gattService
                            ?: throw IllegalStateException("Connection service is unavailable; reopen the app")
                        service.forgetSelectedPhone(phone.address)
                        selectedPhoneAddress = null
                        phoneChoices = phoneChoices?.filterNot { it.address == phone.address }
                        diagnosticMessage = getString(R.string.phone_forgotten)
                        localStatus.value = diagnosticMessage
                    } catch (error: IllegalStateException) {
                        diagnosticMessage = error.message ?: getString(R.string.forget_phone_failed)
                        showMessage(diagnosticMessage)
                    } catch (error: SecurityException) {
                        diagnosticMessage = getString(R.string.forget_permission_denied)
                        showMessage(diagnosticMessage)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { chooser = null }
            .show()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun sendAMSCommand(command: Byte, fromCrown: Boolean = false) {
        val service = gattService
        when {
            !hasPermissions() -> showMessage("Bluetooth and notification permissions are required")
            service == null -> showMessage("No active iPhone connection")
            else -> service.sendAMSCommand(command, fromCrown)
        }
    }
}

@Composable
internal fun PhoneScreenBackNavigation(
    hasPreviousScreen: Boolean,
    navigationBlocked: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    BackHandler(enabled = hasPreviousScreen) {
        if (!navigationBlocked) onBack()
    }
    if (hasPreviousScreen && Build.VERSION.SDK_INT < 36) {
        BasicSwipeToDismissBox(
            onDismissed = { if (!navigationBlocked) onBack() },
            modifier = Modifier.systemGestureExclusion(),
            userSwipeEnabled = !navigationBlocked
        ) { isBackground ->
            if (!isBackground) content()
        }
    } else {
        content()
    }
}

@Composable
fun WearApp(
    amsState: StateFlow<AMSState>,
    onTogglePlayPause: () -> Unit,
    onNextTrack: () -> Unit,
    onPreviousTrack: () -> Unit,
    status: String = "",
    connectionState: PhoneConnectionState = PhoneConnectionState.DISCONNECTED,
    onChoosePhone: (() -> Unit)? = null,
    onCommand: (Byte) -> Unit = {},
    onNotificationApps: (() -> Unit)? = null,
    onCrownVolume: (Byte) -> Unit = onCommand,
    onCrownVolumeEnd: () -> Unit = {}
) {
    val state by amsState.collectAsStateWithLifecycle()
    ANCSTheme {
        AppScaffold {
            ScreenScaffold { contentPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        NowPlayingScreen(
                            state = state,
                            onTogglePlayPause = onTogglePlayPause,
                            onNextTrack = onNextTrack,
                            onPreviousTrack = onPreviousTrack,
                            onCommand = onCommand,
                            onCrownVolume = onCrownVolume,
                            onCrownVolumeEnd = onCrownVolumeEnd
                        )
                        if (onNotificationApps != null) {
                            TextButton(onClick = onNotificationApps) {
                                Text(stringResource(R.string.notification_apps))
                            }
                        }
                        if (onChoosePhone != null) {
                            PhoneConnectionButton(
                                connectionState = connectionState,
                                status = status,
                                onChoosePhone = onChoosePhone,
                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@WearPreviewDevices
@WearPreviewFontScales
@Composable
fun DefaultPreview() {
    WearApp(
        amsState = kotlinx.coroutines.flow.MutableStateFlow(
            AMSState(
                available = true,
                track = AMSTrackInfo(title = "Track Title", artist = "Artist", album = "Album", duration = 230f),
                player = AMSPlayerInfo(isPlaying = true, elapsedTime = 42f, playbackRate = 1f),
                supportedCommands = setOf(AMSCommand.TOGGLE_PLAY_PAUSE, AMSCommand.NEXT_TRACK,
                    AMSCommand.PREVIOUS_TRACK)
            )
        ),
        onTogglePlayPause = {},
        onNextTrack = {},
        onPreviousTrack = {},
        connectionState = PhoneConnectionState.CONNECTED,
        onChoosePhone = {}
    )
}
