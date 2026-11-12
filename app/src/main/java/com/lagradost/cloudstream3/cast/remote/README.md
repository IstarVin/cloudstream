# Cast to CloudStream TV

Control CloudStream installed on a TV from CloudStream on a phone. The phone resolves everything
(links, subtitles, source switching) and only sends the final result and the commands, the TV just plays.

```
PHONE                                              TV (CloudStream open)
cast button -> RemoteCastUi (pick TV, PIN)         RemoteCastReceiver (server, pairing, NSD)
GeneratorPlayer UI (unchanged)                       -> RemoteCastReceiverActivity
  player = RemoteIPlayer  <-- RemoteCast -->           -> RemoteCastReceiverFragment (FullScreenPlayer + CS3IPlayer)
  RemoteMedia3Player (seek bar / media session)
```

## Files

| File | What |
|---|---|
| `RemoteCastProtocol.kt` | Opcodes, messages, framing (FCast style: 4 byte LE size, 1 byte opcode, JSON) |
| `RemoteChannel.kt` | One framed connection: reader thread, serialized writer, ping/timeout |
| `RemoteCastHandshake.kt` | TV side pairing logic (PIN, token), pure Kotlin |
| `RemoteCastDiscovery.kt` | NSD advertise (TV) and discover (phone) |
| `RemoteCastReceiver.kt` | TV: server, pairing, forwards commands to the player screen |
| `RemoteCastReceiver{Activity,Fragment}.kt` | TV: the player screen |
| `RemoteCast.kt` | Phone: connection, pairing, reconnect |
| `RemoteIPlayer.kt` | Phone: an `IPlayer` that remote controls the TV. This is what makes the existing player UI work unchanged |
| `RemoteMedia3Player.kt` | Phone: media3 `Player` mirror so the seek bar / media session keep working |
| `RemoteCastUi.kt` | Phone: cast button chooser, device list, manual IP, PIN dialogs |
| `RemoteCastTracks.kt` | Track conversions |

## Hooks in upstream files (keep these when merging)

| File | Change |
|---|---|
| `ui/player/AbstractPlayerFragment.kt` | default player is `RemoteCast.createPlayerOrNull() ?: CS3IPlayer()` |
| `ui/player/PlayerView.kt`, `ui/player/GeneratorPlayer.kt` | `player is ExoPlayer` became `player is androidx.media3.common.Player` (the proxy is not an ExoPlayer) |
| `ui/result/ResultFragmentPhone.kt` | the existing Chromecast button calls `RemoteCastUi.setUpCastButton` and is always visible |
| `ui/result/ResultViewModel2.kt` | default episode click plays in the app while connected to a TV |
| `MainActivity.kt` | starts/stops `RemoteCastReceiver` (TV only) |
| `ui/settings/SettingsPlayer.kt`, `res/xml/settings_player.xml` | "Allow casting to this device" switch (TV only) |
| `AndroidManifest.xml` | `RemoteCastReceiverActivity` |

Strings live in `res/values/strings_remote_cast.xml` so `strings.xml` never conflicts.

## Behaviour and limits

- CloudStream has to be open on the TV (no background service).
- First connection needs the PIN shown on the TV, afterwards a stored token is used.
- Torrent/magnet, DRM and downloaded (local) files can not be cast; the player moves on to the next source.
- Leaving the player screen on the phone stops the TV. The phone locking its screen does not.
- If the connection drops the phone reconnects with the token, the TV keeps playing meanwhile.

## Tests

`app/src/test/.../cast/remote`: framing and serialization, handshake logic, the channel over real loopback
sockets, `RemoteIPlayer` against a fake TV, and the real controller against a fake TV including reconnect.
