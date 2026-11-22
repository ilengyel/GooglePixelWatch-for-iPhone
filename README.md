# Google Pixel Watch for iPhone

A proof-of-concept Wear OS app that bridges an iPhone's notifications and
Now Playing media controls to a Google Pixel Watch over Bluetooth LE, using
Apple's documented **ANCS** (Apple Notification Center Service)
and **AMS** (Apple Media Service) protocols. The Pixel Watch has no official
iPhone support — this exists because a $400 smartwatch bricking itself the
moment you leave the Android ecosystem is silly.

Huge thanks to [loud-at-heart](https://github.com/loud-at-heart) for the [AMS](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleMediaService_Reference/Specification/Specification.html#//apple_ref/doc/uid/TP40014716-CH1-SW7) implementation!

> This fork has been tested with a Google Pixel Watch 4. Other watch
> generations may behave differently.

## What it does

- **Notifications (ANCS):** subscribes to the iPhone's Notification Center
  and mirrors incoming notifications (app, title, subtitle, message) as
  native Android notifications on the watch, with cached app display names and
  per-iPhone app filtering. Notifications expose the iPhone's supplied action
  labels when positive/negative actions are available.
- **Now Playing (AMS):** subscribes to the iPhone's current media session
  and exposes it through an Android remote media session and native media
  notification. Wear OS renders playback controls; commands go back to the
  iPhone, where audio continues playing. The custom in-app player is replaced
  by a **Media controls** entry point.

### Notification apps and media controls

Open **Notification apps** to enable or block apps observed during the current
iPhone connection. All apps start enabled. Blocking removes their mirrored
notifications immediately; enabling waits for subsequent phone events and does
not replay old alerts. Filters are saved separately for each iPhone and cleared
when that phone is successfully forgotten. App names are cached only for the
current connection; identifiers are shown while names are unavailable.

Tap **Media controls** to open Google's installed Wear OS media-controls app,
or use the **iPhone media** notification. If that app has no accessible launcher,
ANCS shows an error directing you to the native notification controls.
Native screen layout, session selection, auto-launch and crown behavior depend
on the watch's Wear OS version and still require on-device verification.

The session advertises only iPhone-supported commands. Remote volume adjustments
target the iPhone, not watch audio, and use the existing bounded BLE queue
(at most five writes per second, one queued direction and a 500 ms expiry).
Volume and playback state follow phone updates, not button presses.
Native volume adjustment is enabled after the first phone-reported volume arrives.
Skip intervals and shuffle/repeat cycling are exposed as session custom actions;
whether they appear depends on the system UI. The player chooses skip intervals
and may ignore commands. AMS supplies no artwork or arbitrary seek operation.
Playback timing and duration are published without a polling loop; the native
UI can extrapolate progress. The session and notification clear on disconnect
or when Apple Music closes and clears its playback and track information.

Notification buttons use only non-empty labels supplied by the iPhone for
advertised ANCS actions. A negative action may mean Decline rather than Dismiss;
watch swipes remain local. Buttons expire when the notification changes, is
filtered/removed, or the connection ends. Successful writes remove the buttons
to prevent duplicate requests, but do not assume the phone removed the
notification. Rejected actions show an error. Answering a call, when offered,
does not establish call audio on the watch.

These features have automated protocol/UI coverage; on-device behavior and
battery impact still need verification. Filtering avoids notification-text reads
for blocked apps, but initially identifying allowed notifications adds a BLE
transaction and uncached app names require a separate lookup.

## Setup

- You'll need a Pixel Watch that's already been activated by an Android
  phone (the initial Wear OS setup requires Android; after that, this app
  handles the iPhone side).
- In the Pixel Watch's Bluetooth settings, manually pair it with your
  iPhone.
- Open this project in the latest [Android Studio](https://developer.android.com/studio),
  build and install it on your Pixel Watch.
  - Alternatively, you can use [VS Code instead](VSCODE.md) to build and install
    the app wirelessly.
- Grant Bluetooth and notification permissions in the app, then select your
  bonded iPhone from the device picker. The app remembers that device; use
  **Choose iPhone** to change it. It does not guess based on a device name.
- On your iPhone, accept the prompt asking to share **Notifications** and
  **Media Controls** with the watch.
- Play something on your iPhone, then use **Media controls** in the app or the
  **iPhone media** notification on the watch. Text yourself to test notifications.

If notifications or media controls are unavailable, check the iPhone's Bluetooth
sharing settings.

## Architecture

See [Architecture](ARCHITECTURE.md) for a high-level overview of responsibilities,
data flow, connection lifecycle and power-use considerations.

## References

- [Apple Notification Center Service (ANCS) specification](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html)
- [Apple Media Service (AMS) reference](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleMediaService_Reference/Specification/Specification.html)
- A lot of general BLE knowledge scattered across the internet that's
  impossible to properly cite at this point.
