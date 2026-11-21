# Native connection gap analysis

Assessment date: 17 November 2026.

## 1. Scope and current baseline

This is a **notification and media bridge**, not a native Pixel Watch companion
replacement. The comparison is a watch paired with supported Android; some native
features also depend on a Pixel phone, watch generation, carrier or region.
"Implemented" means present in source, not hardware-verified in this assessment.
The [README](README.md) records Pixel Watch 4 testing; battery costs below are
qualitative, not measured.

**Implemented:** ANCS notification title/subtitle/message and updates/removals
for retained notifications, labelled advertised actions, session-cached app names
and per-phone filtering;
AMS title/artist/playback/volume and shuffle/repeat state with supported
play/pause/previous/next, volume, skip and mode-cycling commands, plus opt-in
bounded crown volume;
background service, remembered phone and
automatic reconnection; phone selection, status, diagnostics and forget/unpair.
Connection management is app-level, not full native Settings integration.

Standalone features such as local alarms, watch faces, downloaded music, GPS
workouts, Maps, Wallet and Google Home are not automatically bridge gaps:
availability needs individual verification of account, setup and Wi-Fi/LTE needs.

## 2. Gaps by implementation path

Categories describe the required path, not a delivery commitment. Unless noted,
the capabilities below are missing.

### Watch-only: extend the existing ANCS/AMS bridge

| Capability | Current limitation | Feasibility / boundary |
| --- | --- | --- |
| Notification buttons and watch-to-phone dismissal | Labelled advertised ANCS actions implemented; watch swipes remain local. | A negative action is not always dismissal, and these are not arbitrary Android actions. Stale/filtered/removed actions are rejected. Answer/decline may be available for calls, but **answering does not establish call audio**. |
| App names and per-app filtering | Implemented: session-cached display names and persistent per-phone filters; generic icon remains. | Unknown notifications need an identifier lookup before filtering. Blocked apps skip text/name reads; enabling does not replay alerts. iPhone sharing settings still affect delivery; richer visuals need another path. |
| Notification continuity/history | Retains up to 20; clears mirrored notifications on disconnect. | Larger retention is straightforward, but **ANCS is not durable history or synchronization**. |
| Expanded media controls and metadata | Volume buttons/percentage, opt-in bounded crown volume and expandable skip/shuffle/repeat controls implemented. Album/duration/playback timing remain limited in subscriptions/UI. | Controls follow advertised support and phone-reported state; skip interval is player-defined. No slider or progress ticker. |
| Native Wear media controls, tile and complication | Controls are in-app; tile/complication are starter surfaces. | Expose a proper media session and event-updated surfaces. |
| Reboot recovery and long-idle reliability | Reconnection exists; no boot receiver. | Add reboot recovery and validate long-idle delivery. A foreground service does not guarantee uninterrupted delivery. |
| Phone-left-behind alert | No disconnect alert. | Debounce connection events; disconnect is not reliable proof of distance or theft. |

### Companion required: permissioned data and new transports

Some data subsets may instead use existing account services or another authorized
integration; they are not available through ANCS/AMS alone.

| Capability | Feasible scope / boundary |
| --- | --- |
| Rich notifications and keyboard/dictation/suggested replies | EU companion path below; replies require forwarded notifications supporting text input. ANCS cannot send text replies or provide arbitrary images/layouts. |
| Album artwork and queue browsing | Another authorized integration is needed: AMS exposes queue index/count, not artwork or a full queue listing. |
| Calendar, contacts and reminders, including contact browsing | Permissioned companion/account-service subsets; currently notification text only. |
| Phone <-> watch ringing / find-device | Companion subject to iOS background/audio restrictions. Google web-based watch finding is separate. |
| Camera shutter, preview and controls | Companion-owned camera experience, not unrestricted Apple Camera control; foreground/unlock constraints apply. |
| Health/workout sync to iPhone/Apple Health | Accessible watch-data subsets plus an iPhone HealthKit app, not automatic access to all Fitbit/Google health data. |
| Notifications outside Bluetooth range | Bridge is BLE-only; substantial new transport work. The EU path documents encrypted internet forwarding. |

**EU companion path:** Apple documents Accessory Notifications from **iOS 26.5**
for dismissal, actions and text replies. Customer use requires an **iPhone in the
EU and an Apple Account with an EU country/region**; development/testing is
allowed elsewhere. It requires an iPhone companion, data-provider, transport and
security extensions, and compatible encrypted watch transport, not an ANCS
switch. Related frameworks cover Live Activities and internet transport.
Outside that eligibility, ANCS/AMS remains the practical notification baseline.

### Restricted: native system and ecosystem integration

| Capability | Boundary / possible subset |
| --- | --- |
| New SMS/RCS conversations and message history | No general public iPhone parity path identified. Notification replies do not grant unrestricted iMessage/SMS sending or message-database access. |
| Setup, accounts, restore and phone transfer | Android activation remains required and iPhone Bluetooth pairing is manual; Google provisioning is outside this app's control. |
| DND/Bedtime/Focus sync; silence phone while wearing watch | No generic ANCS/AMS mode-control API. Schedules or supported automations may provide subsets, not exact system parity. |
| Phone alarm sync, snooze/dismiss and adaptive ringing | No general ANCS/AMS access to iPhone Clock alarms. Companion-owned alarms are possible, not native Clock sync. |
| Watch Unlock / lock phone when left behind | Requires system security integration, not ordinary iPhone app access. |
| Carrier number sharing and LTE provisioning | Carrier/Google/Apple ecosystem dependency, not a normal bridge feature. |

### Unproven: telephony

Call audio requires Bluetooth telephony/audio integration such as HFP, not another
BLE characteristic. **Prove stock Wear OS profile access first.** Native dialling
and call history are also restricted and dependent on telephony integration;
permissioned contact browsing is a separate companion feature.

## 3. Power considerations

**Existing safeguards:** event subscriptions instead of notification/media polling;
one selected phone and no scanning loop; serialized BLE operations, coalescing,
stale-read protection, duplicate suppression and silent updates. Reconnection
backs off from **1 second to a 60-second cap**, suspends while Bluetooth is off,
and metadata recovery allows **two attempts per missing-data episode**.
There is no app heartbeat, progress ticker or explicit wake-lock acquisition.
UI collection is lifecycle-aware; diagnostics observation stops with the screen.

**Remaining risks and implementation rules:**

- Reconnect attempts continue indefinitely while the phone is unavailable:
  faster recovery trades off against out-of-range drain.
- Notification storms drive BLE work, vibration and display wakeups. Filter
  early to avoid text requests and alerts; unknown UIDs still need an app
  identifier lookup. Allowed cold notifications gain a separate text transaction
  and uncached names need a lookup. Filtering is not a measured battery saving.
- Keep media progress local and **visible-screen only**, surfaces event-driven,
  and crown-volume writes bounded (implemented: one per 200 ms, one queued
  direction, 500 ms expiry, cancelled on leaving the active focus mode).
  Scrolling titles/animations still cost power.
- Cache names and batch data/health summaries rather than polling or streaming.
  Simple BLE buttons, text and on-demand ringing are relatively cheap;
  dictation/AI suggestions add microphone, compute or network costs.
- Images, live preview, sustained call audio, continuous sensors/GPS and Wi-Fi/LTE
  relay materially change the power profile. Avoid aggressive RSSI scans,
  continuous location tracking and excessive transport retries.

A foreground service does not imply zero idle drain. See
[Architecture](ARCHITECTURE.md) for lifecycle bounds and measurement scenarios.

## 4. Recommended implementation order

1. **Best value, lowest power:** labelled ANCS actions, app-name caching/filtering,
   media volume and additional AMS controls. App names/filtering and media
   controls, including labelled actions and crown volume, are now implemented in
   source with automated coverage. Hardware and battery validation remain outstanding.
2. **Daily usability:** real media session, useful tile/complication, debounced
   disconnect alerts and reboot recovery.
3. **Measure before expanding:** compare stopped, connected-idle,
   notification-heavy and out-of-range operation with wireless debugging off.
4. **Choose a companion direction:** global permissioned data features versus
   richer EU-only notifications.
5. **Treat call audio as a feasibility spike**, not a promise; prove profile
   access before building a dialler.

## 5. References

**Implementation:** [notification lifecycle](app/src/main/java/dev/toastmod/ancs/presentation/ANCSClient.kt),
[rendering](app/src/main/java/dev/toastmod/ancs/presentation/ANCSNotification.kt),
[attribute parser](app/src/main/java/dev/toastmod/ancs/presentation/ANCSResponse.kt),
[AMS state/parser](app/src/main/java/dev/toastmod/ancs/presentation/AMSClient.kt),
[BLE transport/subscriptions](app/src/main/java/dev/toastmod/ancs/presentation/ANCSGattServiceClient.kt),
[service](app/src/main/java/dev/toastmod/ancs/presentation/ANCSAndroidService.kt),
[media UI](app/src/main/java/dev/toastmod/ancs/presentation/NowPlayingScreen.kt).

**Google:** [calls/messaging](https://support.google.com/googlepixelwatch/answer/12674814?hl=en),
[camera remote](https://support.google.com/wearos/answer/11532572?hl=en-AU),
[alarm sync](https://support.google.com/googlepixelwatch/answer/16765734?hl=en),
[Watch Unlock](https://support.google.com/googlepixelwatch/answer/13387340?hl=en).
[DND/Bedtime rollout](https://9to5google.com/2024/01/02/pixel-watch-bedtime-dnd-sync/)
is secondary reporting.

**Apple:** [ANCS](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html),
[AMS](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleMediaService_Reference/Appendix/Appendix.html),
[Accessory Notifications](https://developer.apple.com/documentation/accessorynotifications),
[notification responses](https://developer.apple.com/documentation/accessorynotifications/responding-to-forwarded-notifications),
[Accessory Transport Extension](https://developer.apple.com/documentation/accessorytransportextension),
[Accessory Live Activities](https://developer.apple.com/documentation/accessoryliveactivities).
