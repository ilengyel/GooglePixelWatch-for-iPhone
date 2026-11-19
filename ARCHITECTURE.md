# Architecture

The app is a standalone Wear OS bridge between an iPhone and a Pixel Watch.
It connects directly over Bluetooth LE using Apple's Notification Center
Service (ANCS) and Media Service (AMS). No iPhone companion app or application
cloud backend is required.

```mermaid
flowchart LR
    Phone["iPhone<br/>Notifications and media"]
    Bridge["Watch background service<br/>Bluetooth connection and protocol handling"]
    Notifications["Native watch notifications"]
    UI["Watch UI<br/>Media controls and connection status"]

    Phone <-->|"Bluetooth LE: ANCS / AMS"| Bridge
    Bridge --> Notifications
    Bridge -->|"State updates"| UI
    UI -->|"User commands"| Bridge
```

## Responsibilities

- **Watch UI:** requests permissions, lets the user select a paired phone, and
  displays media and connection state. It sends user actions to the background
  service rather than managing Bluetooth itself.
- **Background service:** owns the connection to the selected phone and keeps
  it active independently of the UI, using Android's connected-device
  foreground-service lifecycle.
- **Bluetooth and protocol handling:** discovers available services, subscribes
  to updates, serializes Bluetooth operations, and validates incoming data.
- **Notification and media handling:** turns phone events into native watch
  notifications and observable media state, and sends supported playback
  commands back to the phone.

These are responsibilities within one Android application, not separate
deployments.

## Data flow

**Notifications:** the iPhone reports an added, changed or removed notification.
The watch fetches the required text, assembles the response, and posts, updates
or removes the corresponding native notification. Redundant updates are
suppressed. Watch dismissals and replies are not sent back to the phone.

**Media:** the iPhone pushes track and playback updates to the watch UI.
User playback actions are queued and sent to the phone; the UI follows the
phone's reported state rather than assuming a command succeeded.

## Lifecycle and recovery

The user pairs the devices in system settings and explicitly selects the phone
in the app. The selection is saved; media state and pending work remain in
memory. The app does not scan for or switch to alternative phones.

The service reconnects after failures with capped exponential backoff and
suspends connection attempts while Bluetooth is off. Operations have timeouts,
queues and retained notifications are bounded, and missing media metadata gets
limited recovery attempts. Notifications or media can operate independently
when only one iPhone service is available.

Closing the UI does not stop the background connection. Closing a connection
clears pending work, media state and mirrored notifications. Android controls
background execution, so uninterrupted delivery is not guaranteed.

## Power use and boundaries

The design is event-driven: it subscribes to phone updates instead of polling,
requests only the data it uses, coalesces redundant work, and stops UI
observation when screens are inactive. There is no playback progress ticker,
application heartbeat or explicit wake-lock acquisition.

See [setup](README.md#setup) for platform prerequisites and
[Windows development](VSCODE.md) for build and test instructions.
