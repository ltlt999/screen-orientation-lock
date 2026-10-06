# Screen Orientation Lock

> A root-free Android utility that locks the screen orientation **globally** — including for apps that declare their own orientation: car launchers, many video/music players, and some games.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.0%2B-3ddc84.svg)](https://developer.android.com)
[![minSdk](https://img.shields.io/badge/minSdk-26-orange.svg)](app/build.gradle.kts)

<p align="center">
  <img src="docs/images/screen-main.png" width="300" alt="Main screen">
  &nbsp;&nbsp;
  <img src="docs/images/screen-permission.png" width="300" alt="Permission guidance">
</p>

<p align="center">
  <b>中文文档 → <a href="README.md">README.md</a></b>
</p>

---

## What makes this different

Most root-free orientation lockers only write the orientation value into `Settings.System`. That is **completely ineffective against apps that declare their own orientation**, because Android's precedence is:

```
app-declared orientation  >  global system setting
```

Car launchers (e.g. Qing Launcher, which declares `sensorLandscape`), many video and music apps, and some games all fall into that category. They snap the screen back, and you cannot lock them.

**This project instead adds a 0×0 overlay window and declares the orientation in its window parameters.** Overlay windows sit above ordinary application windows in the window hierarchy, so WindowManager takes their orientation request into account when computing the display rotation — and the app's own declaration is overridden.

It needs only the "Display over other apps" permission. **No root, no Shizuku.**

### Verified on a real device

With a car launcher declaring `sensorLandscape` in the foreground:

| Action | Display |
|---|---|
| Lock to portrait | `1920x1080` → **`1080x1920`** |
| Hold for 20 seconds | **stays portrait, not a single flip** |
| Kill this app to drop the overlay | the launcher **immediately reclaims landscape** |

That last step is the control experiment — it rules out coincidence and proves the overlay is what's holding the orientation.

---

## Features

- **6 orientation modes**: portrait, reverse portrait, landscape, reverse landscape, current orientation, auto (follow sensor)
- **Persistent notification**: portrait / landscape / unlock / reverse buttons — switch without opening the app
- **Auto-start on boot**: restores the last lock after a reboot
- **Guard mode**: re-applies the lock if something else changes the orientation
- **All-Chinese dark UI**: per-orientation gradient colours, a rotatable phone illustration

> Note: the app's UI is Chinese only. This is intentional — it targets Chinese car head units and tablets.

---

## Install

1. Download `app-release.apk` from [Releases](../../releases)
2. Open it — an amber permission card appears at the top
3. Tap **开启悬浮窗** ("Enable overlay") and turn the switch on in the system settings page
4. Go back and you're set

### Two permission paths

| Permission | Capability | Recommendation |
|---|---|---|
| **Display over other apps** | Forces the display orientation via an overlay — **works on apps that declare their own orientation** | **Recommended**; this alone is enough |
| Modify system settings | Writes the system orientation setting — only affects apps that follow the system | Optional supplement |

**Either one is enough to lock.** With only "Modify system settings", car launchers and similar apps cannot be locked.

---

## Known limitations

**Orientations fixed by the system itself cannot be locked** — for example some car head units' boot splash, or a lock screen pinned by a particular ROM. That is outside what any app can reach; Android exposes no entry point for it.

Also, the overlay requires the app process to be alive. This app holds it in a foreground service, with boot auto-start and a guard. If the system or a Chinese OEM's background manager kills it, the lock goes with it. In that case, allow this app to auto-start in the system's "Auto-start management" and remove background restrictions — the in-app "System adaptation" card deep-links to those pages.

---

## Build

### Requirements

- JDK 21 (Android Studio's bundled JBR works well)
- Android SDK, `compileSdk 36`
- Gradle 8.12 (the wrapper is committed)

```bash
git clone <repo>
cd screen-orientation-lock
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### One gotcha worth knowing

`gradle.properties` hardcodes

```properties
org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr
```

because the original development machine's default JDK was Java 26, which is incompatible with AGP 8.9.3 — without this line the build fails with `Unsupported class file major version`. **On another machine, change this line to your local JDK 21 path**, or delete it and set `JAVA_HOME`.

Note that Gradle only reads this property from `gradle.properties` or `JAVA_HOME` — putting it in `local.properties` has no effect.

### Run the tests

```bash
./gradlew testDebugUnitTest
```

88 unit tests, all running on the JVM. No emulator, no Robolectric.

### Build a signed release

The repository does not contain a signing key. To build a signed release, create `keystore.properties` at the repository root (it is in `.gitignore`):

```properties
storeFile=keystore/release.jks
storePassword=your-password
keyAlias=your-alias
keyPassword=your-password
```

Without this file the release build degrades to unsigned — **the build does not fail**. An open-source project should build straight after `git clone`.

For CI, `release.yml` reads the key from two repository secrets:

| Secret | Contents |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 keystore/release.jks` |
| `KEYSTORE_PROPERTIES` | the full contents of `keystore.properties` |

Without them the workflow still builds, just unsigned.

---

## Code structure

```
app/src/main/java/com/orientlock/
├── OrientLockApp.kt        Application: notification channel, fallback service start
├── MainActivity.kt         Single activity
├── domain/                 Pure Kotlin, zero Android dependencies, JVM-unit-testable
│   ├── NaturalOrientation.kt    Device natural orientation + pure detection (incl. DisplayRotation)
│   ├── OrientationMode.kt       6 modes × Chinese label / storage name / system value mapping
│   ├── OrientationGuard.kt      Guard drift decision
│   ├── AppSettings.kt           Settings data model
│   └── OrientationRepository.kt Data-access interface (dependency inversion, for testability)
├── data/
│   ├── SystemOrientationAccess.kt  Platform reads/writes (the only place touching Settings.System)
│   ├── SystemOrientationWriter.kt  Write policy (probe mutex, double sampling, validate-then-write)
│   ├── SettingsOrientationRepository.kt  DataStore + guard heartbeat
│   └── Preferences.kt              DataStore delegate
├── system/
│   ├── OverlayOrientationController.kt  ★ Overlay-based orientation control (the key trick)
│   ├── OverlayOrientation.kt            Mode → overlay orientation value mapping
│   ├── OrientationService.kt            Foreground service: notification + guard + holds the overlay
│   ├── BootReceiver.kt                  Start after boot / app update
│   ├── NotificationHelper.kt            Notification channel and building
│   ├── PermissionChecker.kt             Permission query interface + Android implementation
│   ├── PermissionIntents.kt             System settings page intents
│   └── ServiceGateway.kt                Service start/stop interface + Android implementation
└── ui/
    ├── theme/                  Colours / typography / theme / mode gradients (single source)
    ├── MainViewModel.kt        UI state and user intents
    ├── MainScreen.kt           Main screen
    ├── AppRoot.kt              ViewModel ↔ theme wiring
    └── components/             PhonePreview / ModeCard / StatusPill / …
```

**Layering principle**: the `domain` layer imports nothing from `android.*`, so every unit test runs on the JVM. All `Settings.System` access is confined to a single class, `SystemOrientationAccess` — the highest-risk surface in the app, and therefore the one with dedicated tests.

---

## Compatibility

- Minimum Android 8.0 (API 26)
- `targetSdk` is 35 rather than 36: starting with Android 16 (API 36), the system **ignores app-declared orientation on tablets and foldables**. Targeting 35 keeps this app effective on large screens.

---

## License

[MIT](LICENSE)
