<h1 align="center"><img src="assets/logo.svg" width="96" height="96" alt="EasePod"></h1>

<p align="center">
	<a href="README.md">English</a>
	&nbsp;&nbsp;&nbsp;|&nbsp;&nbsp;&nbsp;
	<a href="README_zh.md">简体中文</a>
</p>

<p align="center" style="color:gray;">
	A local-first Android music player with a classic iPod-style click wheel and LCD interface.
</p>

<p align="center">
    <img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
    <img src="https://img.shields.io/badge/Android-14%2B%20(API%2034)-3DDC84?logo=android&logoColor=white" alt="Android 14+">
    <img src="https://img.shields.io/badge/License-AGPL--3.0-blue?logo=opensourceinitiative&logoColor=white" alt="License">
    <img src="https://img.shields.io/github/stars/professor-lee/EasePod?style=flat&label=Stars&color=FFC700&logo=github&logoColor=white" alt="Stars">
    <img src="https://img.shields.io/github/forks/professor-lee/EasePod?style=flat&label=Forks&color=60adff&logo=git-fork&logoColor=white" alt="Forks">
    <img src="https://img.shields.io/github/last-commit/professor-lee/EasePod?color=rebeccapurple&logo=git&logoColor=white" alt="Last Commit">
    <img src="https://img.shields.io/github/languages/code-size/professor-lee/EasePod?style=flat&color=blueviolet" alt="Code Size">
</p>

## Project Overview

EasePod is an Android music player built around a physical-feeling interaction model: an upper LCD area and a
centered click wheel, navigated the way a classic iPod was. It targets **Android 14 (API 34) and above** and is
written in Kotlin with Jetpack Compose, Media3, Room, and Proto DataStore.

The host application is a **complete local player on its own**. It never ships sample tracks and never plays audio
on first run; the local library, albums, and playlists render real empty states until you grant access to a music
folder. Cloud music services are optional **separately installed APK plugins** connected over a bounded AIDL
contract, so the host keeps working with none of them installed.

The interface and interaction rules are frozen and documented in-repo: the main menu, the wheel semantics, touch
guard, lock-screen gating, and the Cover Flow animation are treated as a fixed baseline rather than open design
space. See [DEVELOPMENT.md](DEVELOPMENT.md) for the frozen constraints and the iteration log.

## Main Features

- **Classic click-wheel navigation**: `MENU` goes back, center confirms, left/right change track, down toggles
  play/pause, and rotating the wheel moves focus
- **Cover Flow** with bidirectional translate/scale/tilt at roughly 220 ms, looping at both ends and snapping
  immediately when reduced motion is requested
- **Local library over SAF** from a single root folder: root-level audio files become singles, first-level
  subdirectories become albums, and scanning does not recurse. `mp3`, `wav`, `flac`, `ogg`, with
  `cover.png` / `cover.jpg` / `cover.jpeg` / `cover.gif` as album art
- **Playlists and queue**: transactional playlist creation and appends, queue editing, remembered shuffle/repeat
  state, and playback queue checkpoint restore across process restarts
- **Lyrics**: embedded ID3 `USLT`/`SYLT` and Vorbis comments, with a page lyrics overlay
- **Sleep timer** (off / 15 / 30 / 60 minutes)
- **Stream cache** with a configurable size limit and account-scoped cleanup
- **Offline downloads** through WorkManager, with SHA-256 and length validation, authorization-expiry checks, and
  resume
- **Encrypted backup and restore**: Argon2id (64 MiB, t=3, p=1) followed by AES-256-GCM in an `EPBK` container,
  with strict JSON validation on import; credentials are excluded from backups
- **Themes**: built-in silver, black, and OLED black, plus importable `.ep-theme` packages, offline theme assets,
  and per-theme rollback to the previous version
- **Plugin platform**: separately installed APKs discovered and connected over AIDL, with bounded Parcelables,
  explicit certificate approval in both directions, and enable/disable/uninstall control
- **Optional NetEase Cloud Music plugin**: QR-code login, account catalog, search, playback resolution, lyrics, and
  like state; daily recommendations, private radar, and song roam appear as read-only virtual playlists
- **Music source switching** between the local library and a signed-in account, persisted across restarts
- **Touch guard**: blocks direct LCD touches while keeping the wheel, keyboard, accessibility services, and media
  controls usable, with an exception scoped to the current text-input session
- **Lock-screen overlay** using `setShowWhenLocked` with a system-unlock gate for sensitive actions, plus an
  optional full-screen mode that hides only the top system status bar
- **Safe mode**: after two confirmed startup crashes during external plugin or theme loading, cloud services are
  suspended and the built-in silver theme is used, while the local library keeps working
- **Redacted diagnostic preview and export**

## Notes

- First run does not add tracks or start playback. Grant a folder through
  `Settings > Local & storage > Local music folder` before anything is playable
- Local scanning reads the root folder plus one level of subdirectories only; deeper trees are not traversed
- The NetEase plugin has no launcher icon. The host opens its approval page through an explicit intent and
  connects through the `MUSIC_PLUGIN` service intent
- Per-track entitlements and the actual delivered audio quality are decided by the server, not by this app.
  Requested quality is a preference, and the reported quality is always the value the server actually returned
- The NetEase plugin declares no offline-download and no cloud-playlist-write capability. Entries for unsupported
  capabilities are not shown rather than being faked
- The lock-screen overlay is subject to Android background-start restrictions and ROM window policy; it is not
  guaranteed to appear on every screen wake
- Release builds are unsigned unless you supply a keystore. See
  [Release Build and Signing](#release-build-and-signing)
- The offline plugin market lives inside the host APK and installs through the normal Android package installer;
  it does not auto-install or silently enable anything

## Tech Stack

- Kotlin 2.0.21, Java 17 target
- UI: Jetpack Compose (BOM 2024.12.01) with Material 3 and `material-icons-extended`
- Playback: Media3 1.5.1 (`media3-exoplayer`, `media3-session`, `media3-datasource-okhttp`, `media3-common`)
- Storage: Room 2.6.1 through KSP, Proto DataStore via `protobuf-javalite` 4.29.2, `SimpleCache` for streams
- Background work: WorkManager (`work-runtime-ktx`) for offline downloads
- Networking: OkHttp 4.12.0 and Okio
- Images and codes: Coil 2.7.0, ZXing 3.5.3 for QR login
- Cryptography: Bouncy Castle (Argon2id) plus JCA AES-GCM, and the Android Keystore for account credentials
- IPC: AIDL with `Parcelable` envelopes in `:plugin-contract`
- Build: Android Gradle Plugin 8.7.3, Gradle Wrapper 8.9 (distribution SHA-256 pinned), version catalog in
  `gradle/libs.versions.toml`

## Development and Run

### Requirements

- JDK 17
- Android SDK Platform 35 and Build Tools 35.0.0
- `ANDROID_HOME` set, or `sdk.dir` in an untracked `local.properties`
- An Android 14+ device with authorized ADB for the instrumentation suites

Gradle 8.9 is pinned by the wrapper and its distribution checksum is verified, so no separate Gradle install is
needed.

### Build

```sh
./gradlew :app:assembleDebug :netease-plugin:assembleDebug
```

Add `:sample-plugin:assembleDebug` to also build the standalone sample service.

### Tests

```sh
./gradlew :core:test :data:testDebugUnitTest :playback:testDebugUnitTest \
  :plugins:testDebugUnitTest :netease-plugin:testDebugUnitTest :app:testDebugUnitTest
```

Device instrumentation suites need a connected device and are run per class so each suite gets a fresh process:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.easepod.ui.DeviceShellTest
./gradlew :playback:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.easepod.playback.PlaybackDeviceTest
./gradlew :playback:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.easepod.playback.DownloadCoordinatorDeviceTest
```

The local-library device suite runs against the silent fixture in `verification/media`:

```sh
adb push verification/media /sdcard/Download/EasePod-QA
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.easepod.ui.LocalLibraryDeviceTest
```

Grant that `EasePod-QA` folder through the system picker and expect one root single plus two first-level albums
(three silent WAV/FLAC/OGG tracks). Do not point these tests at a personal music folder.

### Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Optional cloud service, install only if you want it
adb install -r netease-plugin/build/outputs/apk/debug/netease-plugin-debug.apk
# Optional standalone sample service
adb install -r sample-plugin/build/outputs/apk/debug/sample-plugin-debug.apk
```

### Release Build and Signing

```sh
./gradlew :app:assembleRelease
```

Without a keystore this produces `app/build/outputs/apk/release/app-release-unsigned.apk`, which cannot be
installed directly. To produce a signable build, copy the tracked template and fill it in:

```sh
cp keystore.properties.example keystore.properties
```

```properties
storeFile=/absolute/path/to/release.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

`keystore.properties` is gitignored and must never be committed. **No signing key is stored in this repository** —
release signing belongs to whoever distributes the build.

### Packaging a Delivery

```sh
./scripts/package-delivery.sh
```

This collects the APKs, the license, and a reproducible source archive into `dist/`, and writes `SHA256SUMS`.
Verify with `sha256sum -c SHA256SUMS` from inside `dist/`. `dist/` is not tracked by git.

## Modules

| Module | Responsibility |
|---|---|
| `app` | Android window, frozen routes, Compose LCD and wheel UI, system flows |
| `core` | Music entities, queue, settings interfaces, wheel geometry (pure Kotlin/JVM) |
| `data` | Single SAF root, Room index and playlists, settings, encrypted backup |
| `playback` | Media3 foreground service, playback queue, audio output, stream cache, offline downloads |
| `plugin-contract` | Bounded Parcelables, AIDL protocol, plugin service base class |
| `plugins` | Built-in service registry, external APK discovery, mutual trust, connection management, theme import |
| `netease-plugin` | Optional NetEase Cloud Music service shipped as a separate APK |
| `sample-plugin` | Separately installed contract sample service, never bundled with the host |

## Plugin Platform

External services are independently installed APKs reached through AIDL. After the installer returns, the host
re-verifies package name, version, plugin identity, and certificate, and only then offers approval; the plugin side
separately verifies and approves the host's signing identity, so a package name alone is not a trust decision.
Nothing is connected until a certificate is explicitly approved and the plugin is enabled.

Plugins declare capabilities, so entries that a service cannot serve are not displayed. Cloud write operations
additionally require a valid private account, network access, and explicit confirmation; a timed-out write keeps a
pending receipt and re-reads remote state instead of assuming success. Offline cloud mutations are never queued or
replayed.

The bundled sample service is the reference implementation:

- [sample-plugin/README.md](sample-plugin/README.md) — build, install, and two-way approval steps
- [plugin-contract/HOME-CATALOG.md](plugin-contract/HOME-CATALOG.md) — the main-menu catalog contract
- [plugin-contract/MEDIA-SOURCES.md](plugin-contract/MEDIA-SOURCES.md) — media and artwork resolution rules
- [plugin-contract/QUALITY.md](plugin-contract/QUALITY.md) — optional quality negotiation
- [plugin-contract/CLOUD-LIBRARY.md](plugin-contract/CLOUD-LIBRARY.md) — cloud favorites and playlist writes
- [plugins/THEME-PACKAGE.md](plugins/THEME-PACKAGE.md) — `.ep-theme` package format and fallback rules
- [netease-plugin/PROTOCOL_SOURCES.md](netease-plugin/PROTOCOL_SOURCES.md) — protocol provenance, implementation
  scope, and the disclaimer and terms-of-service risk statement for the NetEase plugin

No online plugin index or official publishing service is configured. The market ships inside the host APK and the
installation entry point works from local files.

## Settings

The settings tree is fixed by the frozen baseline:

- `Music`: Cover Flow, playlists, artists, albums, songs, genres, search, recently played, and the music source
- `Settings > Themes`: built-in themes, imported themes, and rollback to the previous version
- `Settings > Wheel`: rotation sensitivity, haptics, and click sound
- `Settings > Playback`: shuffle, repeat, volume, network quality preference, actual quality, audio output
- `Settings > Sleep timer`: off / 15 / 30 / 60 minutes
- `Settings > Lock-screen overlay`, `Full screen`, `Touch guard`: all default to off
- `Settings > Plugins`: built-in services, installed plugins, install entry point, and safe mode
- `Settings > Local & storage`: music folder, rescan, folder permission state, cache limit, cache clearing, and
  backup export/import
- `Settings > Offline music`: Wi-Fi-only downloads and the download list
- `Settings > About`: open-source and third-party licenses, reference credits, the privacy statement, and redacted
  diagnostics

The in-app privacy statement reads: the music folder is only read within the scope you grant; account credentials
are held by the music service plugin that owns them; there is no default telemetry and no upload of local music or
search history; backups are passphrase-encrypted and exclude account credentials.

## Controls

Wheel:

- Rotate: move focus
- Center: confirm; on the now-playing page it cycles Normal → Seek → Volume → Lyrics
- `MENU`: back
- Left / Right: previous / next track
- Down: play / pause

Keyboard and remote controls follow the same routing. Sensitive actions (installing a plugin, exporting data,
removing a folder, cloud writes) require a system unlock and then an explicit confirmation.

## Verification

This repository keeps its acceptance evidence under version control instead of only asserting results:

- [verification/README.md](verification/README.md) — environment, reproduction commands, and coverage boundaries
- [data/VERIFICATION.md](data/VERIFICATION.md), [playback/VERIFICATION.md](playback/VERIFICATION.md),
  [plugins/VERIFICATION.md](plugins/VERIFICATION.md) — per-module acceptance records
- [verification/netease.md](verification/netease.md), [verification/music-source.md](verification/music-source.md),
  [verification/window-settings.md](verification/window-settings.md),
  [verification/plugin-install-delete.md](verification/plugin-install-delete.md) — feature-level acceptance records
- Fixed unit-test result XML and raw build/device logs are archived next to those documents, including the runs
  that failed before a fix

Device captures (screenshots and ADB dumps) are large and device-specific, so they are kept only in the local
working tree and are not distributed with the repository.

## Project Status and Known Limitations

- **Internationalization is not done.** All user-visible strings are currently hardcoded Chinese literals in
  Kotlin; there is no `res/values/strings.xml` and no locale variants. Adding string resources and at least one
  translated locale is the largest outstanding task before the app is usable for non-Chinese readers
- Release builds are unsigned unless `keystore.properties` is supplied
- Device acceptance is not exhaustive. Bluetooth playback, headset removal, audio-focus interruption, battery
  restrictions, process death, device reboot, and ROM-specific lock-screen behavior still need per-device
  verification
- For the NetEase plugin, real cloud writes, full track entitlements, and every quality tier are **not** accepted;
  the verified paths were read-only tests on a real account
- Download behavior under real network loss and recovery, and WorkManager requeue under device battery/network
  constraints, still need device validation
- There is no CI workflow, no online plugin index, and no release pipeline configured in this repository yet
- See [DEVELOPMENT.md](DEVELOPMENT.md) for the current acceptance queue and stage-by-stage status

## Related Projects

EasePod is an independent implementation. Its interface was designed with reference to:

- [Classipod](https://github.com/adeeteya/Classipod) — iPod-style interface structure and wheel interaction
  reference (BSD-4-Clause; no code, fonts, or bitmaps are used)
- [CNMPlayer](https://github.com/professor-lee/CNMPlayer) — reference for the cloud service adaptation flow
- [SoundHelix](https://www.soundhelix.com/) — public sample audio used only by the standalone sample plugin

In-app attributions are bundled at `app/src/main/assets/THIRD-PARTY.txt`, with full license texts under
`app/src/main/assets/licenses/`.

## License

EasePod original code and resources are licensed under [AGPL-3.0-only](LICENSE). The full text is also bundled in
the APK at `app/src/main/assets/LICENSE`.

Third-party components and full license texts are listed in [app/src/main/assets/THIRD-PARTY.txt](app/src/main/assets/THIRD-PARTY.txt);
a runtime dependency inventory is generated into `DEPENDENCIES.txt` at build time from
`releaseRuntimeClasspath`. The NetEase plugin additionally carries the upstream license text it was verified
against at [netease-plugin/licenses/WTFPL.txt](netease-plugin/licenses/WTFPL.txt).

Distributors of an AGPL-covered build must provide the corresponding source, build materials, and third-party
notices, and must state how to obtain the corresponding source. This repository does not predefine an external
source host, so the distributor supplies that address. See [DELIVERY.md](DELIVERY.md) for the delivery layout.

The NetEase plugin is unofficial and unaffiliated with NetEase Cloud Music; using it may violate the service terms
and risks account restrictions. Read the disclaimer in
[netease-plugin/PROTOCOL_SOURCES.md](netease-plugin/PROTOCOL_SOURCES.md) before installing it.

---
## Star History

[![Star History Chart](https://api.star-history.com/image?repos=professor-lee/EasePod&type=date&legend=top-left)](https://www.star-history.com/?repos=professor-lee%2FEasePod&type=date&legend=top-left)
