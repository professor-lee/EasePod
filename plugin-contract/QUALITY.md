# Playback quality negotiation

`ResolvePlayback` already carries a `quality` preference. The protocol now recognizes
these optional capability strings:

| Capability | Request value |
| --- | --- |
| `playback.quality.standard` | `standard` |
| `playback.quality.high` | `high` |
| `playback.quality.lossless` | `lossless` |
| `playback.quality.hires` | `hires` |

`playback.resolve` always accepts the default `standard` preference. A service must
declare each additional supported tier both in its manifest and in its handshake.
The host filters requests using the capabilities from the current connection,
including on the first request after reconnecting. A missing or unknown tier falls
back to `standard`. Plugins without the optional capabilities continue to work.

The playback settings menu offers the tiers declared by the active source. With no
active cloud source, it offers the union of enabled services' tiers. It omits the
choice when there is only the default tier or when safe mode is active.

The host stores the preference in Proto DataStore and encrypted settings backups.
Changing it affects subsequent source resolutions; it does not replace the current
queue entry or restart playback. Completed offline files keep their recorded
quality. Each response must report the actual supplied quality in `actualQuality`,
which may differ from the requested preference because of account or item limits.
The playback screen and playback settings show that actual response value.

No protocol record layout changed, and this optional capability extension keeps the
existing protocol version. The host still negotiates only the capabilities that
both parties declare.
