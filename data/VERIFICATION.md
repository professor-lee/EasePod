# Data Verification

## September 8, 2026 Changes

- Local lyrics retain sibling `.lrc` then `.txt` priority, with stable filename and document-ID ordering. Blank sidecars fall back to the next candidate and then embedded tags. Persisted SAF access is checked before and after reading.
- Embedded metadata uses the project's existing Media3 1.5.1 parser. MP3 supports ID3 USLT/ULT, lyrics TXXX/TXX values, and millisecond SYLT/SLT values. FLAC and Ogg support `LYRICS`, `UNSYNCEDLYRICS`, `UNSYNCED LYRICS`, and `SYNCEDLYRICS` comments.
- Embedded reads stop after metadata, limit the audio header read to 8 MiB, limit returned lyrics to 1 MiB, and observe coroutine cancellation between reads. MPEG-frame SYLT timestamps are not converted because they require decoder frame timing. WAV continues to support sidecar lyrics; RIFF-specific embedded lyric chunks are not claimed.
- Recognized `.lrc` and `.txt` sidecars no longer count as unsupported files in scan summaries. The single-root / one-album-directory-depth scan rules remain unchanged.
- Creating a playlist with initial track IDs verifies all references and inserts the playlist and ordered entries in one transaction. Duplicate track IDs remain distinct entries. Missing references leave no newly created playlist.
- Backups preserve `networkQuality`. Older backups without the field use `standard`; unsupported values are rejected.
- Listening history records a separate event for each playback session, preserving title, artist, album, source identity, and time. Removing one event leaves repeated plays, queue entries, playlists, and retained tracks intact. The most recent 100 events are retained, including stable insertion order when timestamps match.
- Room version 2 migrates existing version 1 history to event IDs and metadata snapshots. The migration preserves root permissions, album membership, duplicate playlist entries, queue checkpoints, and backup import markers. Production startup registers the explicit migration.

## Tests Added

`EmbeddedLyricsTest`: six tests covering actual MP3/FLAC/Ogg metadata, UTF-16 USLT, millisecond SYLT, Vorbis key filtering, size/truncation boundaries, and cancellation.

`LibraryRepositoryTest`: three additional tests covering atomic playlist initialization, scan treatment of lyric sidecars, and blank-sidecar fallback through a SAF URI.

`BackupValidationTest`: one additional test covering the quality setting and legacy backup compatibility.

`LibraryRepositoryTest`: two further tests cover repeated history events and metadata snapshots, single-event removal without damaging other references, and deterministic 100-event retention for equal timestamps.

`LibraryMigrationTest`: upgrades an actual SQLite version 1 database constructed from the exported schema, validates it through Room version 2, verifies foreign keys and retained collections, then reopens it and inserts/removes individual events.

Related device coverage adds local artist all-songs navigation and play-all across directories, history event display and single removal, and natural repeat-one history recording without duplicate events from seeking or pause/resume.

The root agent runs Gradle serially for the complete project. The added tests are pending that run; no new Gradle or device test pass is claimed here.

## Audio Fixtures

`src/test/resources/lyrics/embedded.mp3`, `.flac`, and `.ogg` contain 50 ms of generated silence with the lyric tag `[00:01.00]Embedded fixture`. They contain no third-party recordings. They were generated with FFmpeg's `anullsrc=r=8000:cl=mono`, `-t 0.05`, the `lyrics` metadata field, and the `libmp3lame`, `flac`, and `libvorbis` encoders respectively. `ffprobe` confirmed the tag in all three files.

## Remaining Device Coverage

The parser fixtures exercise actual encoded metadata under Robolectric. Physical DocumentsProvider latency/cancellation, file-backed MP3/FLAC/Ogg lyric display, and SAF grant revocation during a read still require the root agent's device flow checks.
