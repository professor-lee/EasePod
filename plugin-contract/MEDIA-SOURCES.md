# Media Source Identity and Integrity

`PlaybackSource` remains protocol 1.0 and uses a size-framed record. Its optional trailing `contentSha256` field is the hexadecimal SHA-256 of the complete media representation. Old readers skip this field; new readers treat an old frame as `null`. It never authorizes a download by itself: `cachePolicy` must still be `OFFLINE`, and the host rechecks the active source and account.

`length` is the complete byte length, or `-1` when unknown. Provide it when available. A non-null `contentSha256` must contain exactly 64 hexadecimal characters. The host verifies the complete file against this value before publishing it, and checks its locally recorded digest again before offline reuse.

Paused downloads retain their partial file. Resume starts with a fresh playback resolution and may request a nonzero byte position only when the newly resolved SHA-256 matches the retained representation and the known length has not changed. Missing or changed integrity metadata causes a fresh download from byte zero. A plugin that omits the digest therefore supports download and retry, but cannot promise byte-range resume. Unknown length requires a provider digest before completion can be accepted.

For reusable stream caching, `resolverRevision` must identify a stable media representation, and change whenever its bytes change. `actualQuality` is also part of the cache identity. An empty revision disables reusable caching. The host cache key includes source, account, remote media ID, actual quality and revision; signed URL parameters and transport headers are excluded. Reissuing a signature for the same representation can reuse bytes, while changing quality or revision cannot.

The SoundHelix sample remains `NO_STORE` and does not exercise offline download authorization. Offline consistency tests use explicit integrity fixtures and do not save public sample media.
