# Changelog

Progress in this fork since the original fork baseline. Versions follow
Semantic Versioning and are listed newest first. Keep each version in sync with
the app's `versionName` in `app/build.gradle.kts` and increment `versionCode`
for each app version.

## [2.0.0] - Unreleased

### Added

- Windows and VS Code workflows for building, pairing, connecting to, and
  installing the app on a watch over wireless ADB.

### Improved

- ANCS and AMS connection handling, reconnects, and notification lifecycle
  behavior.
- Updated Android Gradle plugin, Kotlin/Compose, Wear libraries, coroutines,
  Guava, and test dependencies to current stable releases.
- Raised compile SDK to 37.2 for Compose; minimum and target SDKs are unchanged.
- Replaced connection text with a phone status icon and matching app branding.
- Added selected-phone diagnostics and a confirmed, individual Forget action
  that reports unsupported unpair requests without resetting Bluetooth.
- Moved connection info into each device row in the phone selection list.
- Enabled crown scrolling on the connection diagnostics page.

### Tests

- Expanded automated coverage for media state, notification lifecycle, Android
  service behavior, and Bluetooth GATT operations.
