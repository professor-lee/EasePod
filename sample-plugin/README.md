# EasePod Sample Plugin

This is a separately installed Android APK for protocol verification. It is not bundled or enabled by EasePod. It provides three public SoundHelix instrumental sample tracks through standard browse, search, details and resolve operations. Lyrics and account reads return empty lists. Authentication, cloud mutations and offline downloads are unsupported.

The plugin does not contact an API or store credentials. Playback makes the host contact `www.soundhelix.com`, whose public sample media is subject to the upstream terms. There is no published privacy policy or plugin index for this development artifact; the manifest deliberately does not invent one. Do not distribute it as an official music provider.

## Local Verification

1. Build the host and `:sample-plugin:assembleDebug` separately.
2. Install the sample APK using the Android installer, or use ADB on a development device.
3. In EasePod plugin settings, inspect and explicitly approve the installed certificate fingerprint, then enable the plugin.
4. From the EasePod plugin details, choose the host-approval action. This opens the sample's explicit `APPROVE_HOST` activity; approve the installed `app.easepod` certificate and return to EasePod to retry its handshake. The APK intentionally has no `MAIN`/`LAUNCHER` entry or desktop icon.
5. Browse or search the sample catalog. Playback uses HTTPS and `NO_STORE`.

The sample service runs in a separate `:music` process. Every Binder entry captures the calling UID before starting work, checks its sole package and approved signing lineage, and requires the current connection token. A package-name string alone is never sufficient.

`./gradlew :sample-plugin:connectedDebugAndroidTest` builds and installs the independent test APK on a connected API 34+ device. The instrumented tests verify real remote Binder proxies, protocol negotiation, forged host rejection, catalog and resolve DTOs, unsupported auth, page limits, deadlines, cancellation, concurrency, generation changes and process death. The new manifest assertion also checks that no launcher entry is exported; it is pending execution in the next device batch. A dedicated debug-only fault fixture is omitted from the release build and has no discovery intent filter. Test setup approves the sample's own test-caller certificate; it does not auto-approve the EasePod host.

After validation, uninstall `app.easepod.sampleplugin.test` and `app.easepod.sampleplugin` to restore a host with no external plugins installed.
