# Google Pixel Watch for iPhone

A proof-of-concept Wear OS app that bridges an iPhone's notifications and
Now Playing media controls to a Google Pixel Watch over Bluetooth LE, using
Apple's documented **ANCS** (Apple Notification Center Service)
and **AMS** (Apple Media Service) protocols. The Pixel Watch has no official
iPhone support — this exists because a $400 smartwatch bricking itself the
moment you leave the Android ecosystem is silly.

Huge thanks to [loud-at-heart](https://github.com/loud-at-heart) for the [AMS](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleMediaService_Reference/Specification/Specification.html#//apple_ref/doc/uid/TP40014716-CH1-SW7) implementation!

> **Original proof of concept tested on: Google Pixel Watch 2.** The current
> service lifecycle, reconnect, and response-handling changes still need
> revalidation with a real watch and iPhone. An emulator cannot establish the
> real iPhone BLE connection or validate battery consumption. Other Pixel
> Watch generations may behave differently.

## What it does

- **Notifications (ANCS):** subscribes to the iPhone's Notification Center
  and mirrors incoming notifications (app, title, subtitle, message) as
  native Android notifications on the watch. Fragmented attributes are assembled
  before posting; edits update the same notification, and removals cancel it.
  Silent and pre-existing notifications do not alert. The watch retains at most
  20 notifications and requests up to 511 bytes each for title, subtitle, and message.
  Updates to evicted notifications are ignored, so they cannot re-alert or displace
  retained notifications. Service startup clears stale mirrored notifications
  left behind by process death without removing the connection notification.
- **Now Playing (AMS):** subscribes to the iPhone's current media session
  and shows a live-updating control card — track title/artist, play/pause,
  and skip previous/next — that sends real remote-control commands back to
  the phone. Buttons are enabled only for commands the iPhone reports as supported.
  The service owns the connection and media state, so closing the app does not
  stop reception, and reopening it does not create another connection.
- **Marquee text:** track title/artist scroll only when they don't fit,
  using Compose's `basicMarquee`, so idle/short text never animates and
  never drains battery for no reason.
- **Self-healing Track metadata:** iOS doesn't always resend the current
  track's title/artist when the watch (re)connects to a song that was
  already playing — a known AMS quirk. This app detects that stuck state
  (player reports "playing" but no track metadata ever arrived) and makes up to
  two delayed subscription-cycle attempts per missing-metadata episode, rather
  than polling forever. It subscribes only to title, artist, and playback state;
  truncated media values trigger serialized full-attribute reads.
- **Reconnects:** unexpected disconnects retry with increasing delays up to
  60 seconds. Service changes trigger a fresh connection and service discovery.
  Pending requests, partial data, and mirrored notifications are cleared on
  disconnect; iOS notification identifiers are scoped to a connection session.

## Setup

- You'll need a Pixel Watch that's already been activated by an Android
  phone (the initial Wear OS setup requires Android; after that, this app
  handles the iPhone side).
- In the Pixel Watch's Bluetooth settings, manually pair it with your
  iPhone.
- Open this project in the latest [Android Studio](https://developer.android.com/studio),
  build and install it on your Pixel Watch.
- Grant Bluetooth and notification permissions in the app, then select your
  bonded iPhone from the device picker. The app remembers that device; use
  **Choose iPhone** to change it. It does not guess based on a device name.
- On your iPhone, accept the prompt asking to share **Notifications** and
  **Media Controls** with the watch.
- Play something on your iPhone and open the app on the watch — the Now
  Playing card should show up with working controls. Text yourself to test
  notifications.

Connection and protocol failures appear in the app's status text and device
logs. If permission is revoked, reopen the app to grant it again. If notification
or media services are unavailable, check the iPhone's Bluetooth sharing settings.

### Validation

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Unit tests cover fragmented notification responses, media state handling,
notification eviction/updates, and stale-notification cleanup on service creation.
Before relying on the bridge, test on a real watch: first-run permissions,
device selection, repeated app opening, long notifications, updates/removals,
silent notifications, media controls, and disconnect/reconnect while playing.
Battery efficiency requires real-device measurement.

### Windows / VS Code development

Android Studio is optional for building and installing from VS Code. Install a
full **JDK 21** (not a JRE), Android SDK command-line tools, `platform-tools`,
the **Android API 36.1** platform, and the build-tools version requested by
Gradle. The current command-line tools include Android CLI:

```powershell
android --no-metrics sdk install "platform-tools" "platforms;android-36.1" "build-tools;36.0.0"
```

Review and accept Google's SDK terms when installing. With older command-line
tools, use `sdkmanager` and `sdkmanager --licenses` instead. Set user environment
variables `JAVA_HOME` to the JDK directory and `ANDROID_HOME` to the SDK directory;
add their `bin` / `platform-tools` directories to your user `Path`. Restart
VS Code after changing environment variables.

Install the workspace's recommended extensions: **Kotlin by JetBrains**,
**Gradle for Java**, and **PowerShell**. Kotlin's Android support is experimental;
use Android Studio for integrated Compose previews and Android debugger tooling.
The tasks below use Gradle and ADB directly, independent of extensions.

1. Put the PC and watch on the same Wi-Fi network.
2. On the watch, open **Settings > System > About > Versions** and tap
   **Build number** seven times to enable developer options.
3. Under **Settings > Developer options**, enable **ADB debugging** and
   **Wireless debugging**, and allow your Wi-Fi network.
4. Select **Wireless debugging > Pair new device**. In VS Code, run
   **Terminal > Run Task > Watch: Pair (enter code in terminal)**, enter the
   displayed `IP:pairing-port`, then enter the pairing code in the task terminal.
   Do not save the pairing code in workspace files.
5. Return to the main **Wireless debugging** screen. Run **Watch: Connect**
   using that screen's `IP:connection-port`. This is usually a **different port**
   from the pairing port and can change when wireless debugging restarts.
6. Run **Watch: List devices** and check the watch is listed as `device`.
7. Run **Watch: Build, install and launch** using the connection address.
   It installs the debug APK on that specific device without removing app data
   and opens the app. Updating an APK signed by a different key will fail;
   uninstalling the existing app first deletes its data.

**Ctrl+Shift+B** builds the debug APK without installing it.
**Watch: Logcat** streams device logs; stop it with **Ctrl+C**.
The same workflow is available from PowerShell:

```powershell
.\scripts\Watch.ps1 -Action Build
.\scripts\Watch.ps1 -Action Pair -Target "192.168.1.50:PAIRING_PORT"
.\scripts\Watch.ps1 -Action Connect -Target "192.168.1.50:CONNECTION_PORT"
.\scripts\Watch.ps1 -Action Install -Target "192.168.1.50:CONNECTION_PORT"
```

Use the ports shown on your watch, not the placeholders above. If connections
fail, check for Wi-Fi client isolation or try a shared hotspot. Disable wireless
debugging when finished. Bluetooth debugging is not supported on Wear OS 3+.
See Google's [Wear OS wireless debugging guide](https://developer.android.com/training/wearables/get-started/debug-wifi).

## References

- [Apple Notification Center Service (ANCS) specification](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html)
- [Apple Media Service (AMS) reference](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleMediaService_Reference/Specification/Specification.html)
- A lot of general BLE knowledge scattered across the internet that's
  impossible to properly cite at this point.
