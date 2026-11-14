# VS Code development on Windows

This workflow builds and installs the app from VS Code without Android Studio.

## Requirements

- VS Code and a full JDK 21 installation (not a JRE).
- Android SDK command-line tools, Android API 37.2, build-tools 36.0.0, and
  platform-tools. For current command-line tools, install the SDK components:

  ```powershell
  android --no-metrics sdk install "platform-tools" "platforms;android-37.2" "build-tools;36.0.0"
  ```

  Accept the SDK licenses when prompted. With older command-line tools, use
  `sdkmanager` and `sdkmanager --licenses`.
- Set the user environment variables `JAVA_HOME` to the JDK directory and
  `ANDROID_HOME` to the SDK directory. Restart VS Code after changing them.

Install the recommended VS Code extensions: **Kotlin by JetBrains**,
**Gradle for Java**, and **PowerShell**. Kotlin's Android support is
experimental; use Android Studio for integrated Compose previews and Android
debugger tooling.

## Connect to the watch

1. Put the PC and watch on the same Wi-Fi network.
2. On the watch, open **Settings > System > About > Versions** and tap
   **Build number** seven times to enable developer options.
3. Under **Settings > Developer options**, enable **ADB debugging** and
   **Wireless debugging**, and allow your Wi-Fi network.
4. Choose **Wireless debugging > Pair new device**. In VS Code, run
   **Terminal > Run Task > Watch: Pair (enter code in terminal)**. Enter the
   displayed `IP:pairing-port`, then enter the pairing code in the task
   terminal. Do not save the pairing code in workspace files.
5. Return to the main **Wireless debugging** screen and run **Watch: Connect**
   with its `IP:connection-port`. This is usually a different port from the
   pairing port and may change when wireless debugging restarts.
6. Run **Watch: List devices** and confirm the watch appears as `device`.

## Build and install

- Run **Terminal > Run Build Task** or press **Ctrl+Shift+B** to build the
  debug APK without installing it.
- Run **Terminal > Run Task > Watch: Build, install and launch** to build,
  install, and open the app on the connected watch. The task preserves app
  data; installing an APK signed with a different key requires uninstalling
  the existing app first, which deletes its data.
- Run **Watch: Logcat** to stream device logs; stop it with **Ctrl+C**.

The same actions are available from PowerShell:

```powershell
.\scripts\Watch.ps1 -Action Build
.\scripts\Watch.ps1 -Action Pair -Target "192.168.1.50:PAIRING_PORT"
.\scripts\Watch.ps1 -Action Connect -Target "192.168.1.50:CONNECTION_PORT"
.\scripts\Watch.ps1 -Action Devices
.\scripts\Watch.ps1 -Action Install -Target "192.168.1.50:CONNECTION_PORT"
.\scripts\Watch.ps1 -Action Logcat -Target "192.168.1.50:CONNECTION_PORT"
```

Replace the example addresses and ports with those shown on the watch. If
connections fail, check for Wi-Fi client isolation or try a shared hotspot.
Disable wireless debugging when finished. Bluetooth debugging is not supported
on Wear OS 3+; see Google's
[Wear OS wireless debugging guide](https://developer.android.com/training/wearables/get-started/debug-wifi).

## iPhone connection diagnostics

In ANCS, tap the phone icon to select a paired device. The selected radio button,
Selected label, and Bluetooth address suffixes distinguish devices with the same name. Each connection
row has an info icon that opens its diagnostics without changing the selection.
Diagnostics show the full Bluetooth address.
ANCS connection/media status and Forget are available for the selected phone only.

**Forget phone** requires confirmation and attempts to remove only that Bluetooth
bond. Android may block the system unpair API; failures are shown without resetting
Bluetooth or the watch. The saved selection is cleared only after bond removal is
confirmed. Unpairing the watch's setup phone may disrupt its companion connection.
