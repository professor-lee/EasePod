# EasePod Theme Package Schema

An `.ep-theme` file is a ZIP archive containing `manifest.json`, `tokens.json`, and optional bitmap files beneath `assets/`. Theme packages contain no executable code. This schema implements only the constrained set of theme slots the host exposes. A theme cannot change routing, dangerous-action confirmation, or input semantics.

## Manifest

```json
{
  "manifestVersion": 1,
  "kind": "theme",
  "pluginId": "org.example.graphite",
  "displayName": "Graphite",
  "version": "1.0.0"
}
```

All five fields are required. Unknown fields are rejected. `pluginId` is a lowercase dotted or hyphenated ID, up to 128 characters; `theme.bundled.*` and `core.*` are reserved. Names cannot contain control characters and are limited to 80 characters. Versions contain 1-64 ASCII letters, digits, `.`, `_`, `+`, or `-` and must begin with a letter or digit.

## Tokens

```json
{
  "colors": {
    "frameTop": "#E4E8EA",
    "frameBottom": "#677176",
    "wheel": "#FDFDFD",
    "key": "#52616B",
    "centerTop": "#B1B1B0",
    "centerBottom": "#E1E1E1",
    "highlight": "#2878BD"
  },
  "font": "sans",
  "fontSize": "standard",
  "spacing": "standard",
  "assets": {
    "shellTexture": "assets/shell.png",
    "albumPlaceholder": "assets/album.png"
  }
}
```

Every token is optional. `{}` preserves the frozen silver theme, system font, menu sizes, and spacing. Unknown top-level tokens and unknown color names are rejected. Invalid values for recognized color or typography tokens fall back to the host default.

| Token | Accepted Values | Host Default |
| --- | --- | --- |
| `font` | `system`, `sans`, `serif`, `monospace` | `system` |
| `fontSize` | `standard`, `large` | `standard` |
| `spacing` | `standard`, `relaxed` | `standard` |

Fonts map to Android's installed font families. Themes cannot supply fonts, font URLs, weights, or arbitrary text scales. Font families apply to display text; accessibility labels and editing behavior remain host-owned.

`standard` font size uses 15 sp for menu rows and 19 sp for large root menu rows. `large` uses 17 sp and 21 sp. System accessibility scaling remains applicable. This token affects menu text only; confirmation dialogs, input fields, lyric timing, and wheel labels retain their host styles.

`standard` spacing uses minimum row heights of 32 dp / 42 dp (ordinary / large root menu), with 7 dp horizontal and 4 dp vertical padding. `relaxed` uses 38 dp / 48 dp with 9 dp / 7 dp padding. These tokens never change routes, ordering, focus rules, touch targets on the wheel, or the LCD/wheel division.

Colors use `#RRGGBB` or opaque `#FFRRGGBB`. Transparent or malformed colors fall back. Wheel labels must have at least 3:1 contrast against the wheel, and the selected-row highlight must support at least 3:1 contrast with white text; invalid combinations receive a readable fallback.

## Bitmap Slots

| Slot | Rendering Contract |
| --- | --- |
| `shellTexture` | Static bitmap beneath the device content, clipped to the outer shell. It cannot cover the LCD, wheel controls, dialogs, or text. |
| `albumPlaceholder` | Static artwork shown only when a track/album has no usable cover or its cover fails to load. The host retains its original placeholder if the resource becomes unavailable. |

Asset paths are relative, case-sensitive references to files under `assets/`. Only PNG, JPEG (`jpg`/`jpeg`), GIF, and WebP are accepted; animation is not a theme capability, so the host uses a static decoded bitmap. Every declared slot must reference an existing bitmap with matching file extension and content. Unknown slots, `null`, URLs, absolute paths, traversal, and references to JSON are rejected. Both slots can reference the same bitmap.

Unreferenced valid resources are permitted within archive limits but are never loaded by the UI. Resource filenames cannot alter routes, executable behavior, or accessibility labels.

## Validation and Publication

Archives are limited to 10 MiB compressed, 30 MiB expanded, and 200 entries. Each JSON file is limited to 64 KiB and each bitmap to 4 million pixels. The host validates UTF-8, strict JSON syntax, duplicate fields, ZIP CRC/size, image decoding, path collisions, symbolic links, and file types. Arbitrary HTML, JavaScript, DEX, SVG, native libraries, and external fonts are rejected.

Installation validates an isolated staging directory, moves it to a new immutable version directory, validates the published directory, and then atomically updates the registry. Failed installation preserves the current version. One previous verified version is retained. Rollback validates that version again before switching; a corrupt previous version cannot replace the current one. Invalid installed themes are omitted during startup, and selection falls back to bundled silver.

`ThemeManager.theme(id)` returns the installed `ThemeInfo`, or bundled silver for an unavailable ID. `ThemeInfo.typography` exposes the accepted enums. `ThemeInfo.assets` exposes absolute paths within the current validated version directory; paths change when the version changes and return to the previous directory on rollback. Consumers should use those paths as image cache identities and handle missing files with the host fallback.

`inspect()` returns palette and typography metadata without asset paths, because its staging files are deleted before returning. It does not publish resources or change the active selection. Installed-theme preview can use `ThemeManager.theme(id)`; cancelling preview restores the previously selected theme.

## Verification

`ThemeArchiveTest` covers archive limits and path validation, readable fallback tokens, accepted typography, asset slot validation, published resource paths, failed updates, version rollback, and rejection of a corrupted previous resource.

```sh
./gradlew :plugins:testDebugUnitTest \
  --max-workers=2 -Pkotlin.incremental=false
```

在仓库根目录执行，需要 JDK 17（Gradle 8.9 不支持更高版本）与 Android SDK Platform 35。

The four typography/asset additions await execution in the next unified build. UI rendering of these tokens is verified separately in the application suite.
