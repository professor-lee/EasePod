# Playback Verification

Verified on 2026-09-08 using JDK 17, Gradle 8.9, Android SDK 35, and a HiBreak device running Android 14 / API 34.

## Latest Passed Batches

- `:playback:testDebugUnitTest`: 10/10, with no failures, errors, or skips. The suite passed again after the download observation fix. It covers queue occurrence identity, shuffle restoration, bounded reordering, source/account cache isolation, offline expiry, HTTPS/header validation, exact media domain matching, and private/reserved address rejection.
- `PlaybackDeviceTest` and `SourceCacheDeviceTest`: 14/14 in 7.342 seconds, comprising 11 playback tests and 3 cache tests. [Device log](../verification/window-settings-playback-device.txt).
- `DownloadCoordinatorDeviceTest`: 13/13 in 4.799 seconds after the observation fix. The deterministic delayed-queue-event test also passed separately, 1/1 in 0.291 seconds. [Full suite](../verification/window-settings-download-fixed-suite.txt), [single regression](../verification/window-settings-download-race-device.txt).
- `SampleServiceDeviceTest`: 1/1 in 56.524 seconds. The separately installed and approved SoundHelix service completed actual public HTTPS playback, advancing position and seeking; disabling the service stopped playback, and re-enabling did not restart it. [HTTPS integration log](../verification/window-settings-https-device.txt). This public, account-free stream does not establish authenticated streaming or offline download support.
- Debug library and instrumentation APK compilation passed, including the added download regression. [Build log](../verification/window-settings-download-fix-build.txt).

The playback fixture is a generated, silent 30-second PCM WAV served by a test-only FileProvider. Playback tests now include the previously pending `directPlaybackRegistersMediaSessionAndContinuesInForegroundService` and `accountSignOutCancelsOnlyItsResolutionAndQueueAvailability`, plus authorization changes without a UI and repeat-play history events. Cache tests cover refreshed URLs, quality/revision separation, no-store behavior, and scoped account removal.

## Reproduction

Run from the project root with JDK 17 and Android SDK 35 configured. Keep the download suite in a separate instrumentation process because its WorkManager test configuration differs from the playback service suite.

```sh
./gradlew :playback:testDebugUnitTest :playback:assembleDebugAndroidTest
./gradlew :playback:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.easepod.playback.PlaybackDeviceTest,app.easepod.playback.SourceCacheDeviceTest
./gradlew :playback:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.easepod.playback.DownloadCoordinatorDeviceTest
```

Reports and preserved evidence:

- [Window-settings verification](../verification/window-settings.md) indexes the current application and module batches.
- `../verification/window-settings-unit/TEST-app.easepod.playback.PlaybackRulesTest.xml` preserves this iteration's 10-test unit suite; `build/test-results/testDebugUnitTest/TEST-app.easepod.playback.PlaybackRulesTest.xml` records the later passing rerun after the observation fix.
- `build/outputs/androidTest-results/connected/debug/TEST-HiBreak - 14-_playback-.xml`
- `build/outputs/androidTest-results/connected/debug/HiBreak - 14/` contains per-test device logs.

Filtered runs may replace Gradle's default reports. The linked text logs identify each preserved batch; results from different batches are not one combined execution.

## Implemented Behavior

- Queue edits now use Media3 add/remove/move operations when the current occurrence is retained, avoiding unnecessary source preparation.
- A later pause cancels pending playback intent while the queue is being saved; platform next uses the controller's queue semantics without recursive forwarding.
- Stop cancels source resolution and sleep while preserving the queue and position.
- Cache resizing uses the same monitor order as SimpleCache callbacks.
- Cloud streams and downloads use a shared HTTPS transport: explicit redirect handling, exact permitted hosts, DNS address checks, and removal of source headers on cross-host redirects.
- HTTP 401/403 permits one fresh resolution. Further authorization failure is excluded from Media3 automatic retries.
- Download cancellation/deletion is serialized against file completion; offline reuse revalidates grant, length, checksum, and source availability.
- Account sign-out uses a source/account key to cancel only that account's resolution and active downloads, stop its current media, and deny its stream/offline cache access. Re-enabling an account does not start playback.
- Volume steps now correspond to integer Android MUSIC stream levels. The library declares MODIFY_AUDIO_SETTINGS.
- Download operations now wait for persisted-request recovery before accepting new work. A Wi-Fi preference changed during recovery is retained and applied to pending requests.
- A source/account permission revoked while offline-policy lookup or media resolution is suspended is rechecked before the next provider call or opening a transport. Completion and offline reuse also recheck expiry after file hashing.
- Cancellation cleanup no longer removes a partial file belonging to a request that has since resumed.
- Shutdown cancels and joins observation jobs before closing the database; synchronized database access rejects late operations without reopening the released helper.
- A queued WorkInfo snapshot received after a transfer enters DOWNLOADING is checked against the current WorkManager record before changing the phase. A currently RUNNING worker keeps its transfer state; a real transition back to ENQUEUED or BLOCKED still updates the queued/waiting display.

## Download Integration Coverage

The 13 passing tests use real SQLite storage and WorkManager's test scheduler. A test Worker delegates to the production coordinator, and a controllable byte DataSource exercises transfer failures without changing the production HTTPS policy. Fixture databases and files use unique names and are removed after each test.

Coverage includes complete-file bytes, SHA-256 and quality/seek metadata, checksum mismatch cleanup and retry, pause/resume with retained byte position and a new request ID, Wi-Fi constraint replacement and rejection of an old worker, account revocation during each asynchronous provider call, tampering with a completed file, changed/missing provider checksums, authorization initialization after recovery, unavailable-account offline denial without deleting valid files, and shutdown with a pending resolution.

The new delayed-event regression pauses an actual WorkManager Worker inside its DataSource, then delivers its earlier ENQUEUED snapshot to the same reconciliation function used by the observer. It verifies that the phase remains DOWNLOADING while WorkManager reports RUNNING, then allows the transfer to finish and confirms COMPLETE and offline reuse.

These fixtures do not verify real download TLS, HTTP Range support, connectivity changes, or the download foreground notification. The separately passing HTTPS playback test does not replace those checks.

## Historical Batches

- Earlier on 2026-09-08, 8 unit tests and 6 controller device tests passed. Their original reports remain in `verification/2026-09-08-rules.xml` and `verification/2026-09-08-device-controller.xml`. They cover the earlier implementation and are superseded by the larger suites above.
- The earlier [nine-test download batch](../verification/download-device-final.txt) crashed while an observer accessed a closed SQLite connection pool. The later shutdown fix and `releaseStopsObserversAndLateResolutionCannotReopenDatabase` are included in the passing 13-test suite.
- Before the queued-event fix, [the 12-test batch](../verification/window-settings-download-device.txt) had one failure: `completedOfflineFileDoesNotBypassUnverifiedAccountAndRemainsRecoverable` expected COMPLETE but observed FAILED. A [single rerun](../verification/window-settings-download-repro.txt) and [12-test rerun](../verification/window-settings-download-recheck.txt) passed, but those reruns alone did not establish a fix.
- Code review then identified that a delayed ENQUEUED/BLOCKED snapshot could reset an active transfer to a queued phase and cause cancellation. The latest batch includes the current-state check and deterministic regression described above; the original failure log does not identify its exact event sequence.

## Remaining Validation

- Account-authorized HTTPS streaming, redirect scenarios, and complete external-provider download/pause/resume remain unverified. Public SoundHelix streaming and seeking have passed separately.
- Bluetooth, physical headset removal, audio focus interruption, battery restrictions, process death, and device reboot have not been tested in this module's device suite.
- Changed Wi-Fi preferences and rejection of old request IDs passed in the controlled download suite. Real connectivity loss/recovery and WorkManager requeue behavior under device battery/network constraints still need device validation.

Do not run concurrent Gradle compilations against this workspace's same module outputs. An earlier overlapping build caused a Kotlin incremental cache EOF error; sequential builds with incremental compilation disabled passed.
