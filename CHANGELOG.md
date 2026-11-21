# Changelog

Changes from the original fork baseline. Versions follow Semantic Versioning,
newest first; keep `versionName` in sync and increment `versionCode` for each
app version.

## [2.0.1] - 2026-11-19

- Cached app names and per-iPhone notification filters.
- iPhone-provided notification action buttons with stale-action protection;
  watch swipes remain local.
- Expanded media controls for volume, skip, shuffle and repeat, plus opt-in
  crown volume without polling.
- Prevent duplicate notification updates and dismissed alerts reappearing after
  app-name or action-completion updates.
- Enable Gradle configuration and local build caching for faster repeat builds.

## [2.0.0] - 2026-11-15

- Windows and VS Code workflows for building and wireless ADB setup/install.
- Explicit iPhone selection, per-device diagnostics with crown scrolling, and
  confirmed individual unpairing without resetting Bluetooth.
- Improved ANCS/AMS handling and automated coverage for media, notifications,
  service lifecycle, and Bluetooth operations.
- Phone connection status icon and updated app branding.
- Updated build/runtime/test dependencies and compile SDK 37.2; minimum and
  target SDKs unchanged.
