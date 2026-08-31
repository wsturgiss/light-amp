# What Amp needs from the SDK

Amp ships today, but parts of it are built around the SDK because there is no
supported route. Each item below is a workaround we would delete the day an API
exists. Checked against `upstream/main` at `3df3c24` (SDK 0.1.1, 30 Aug 2026) —
which is also the commit the `light-sdk` submodule is pinned to.

## Workarounds we would drop

### Background audio — CLOSED, adopted 31 Aug 2026

The gap that started the spikes is gone: Amp declares
`capabilities = ["detached-audio"]` and plays through the SDK's own
`LightAudioService`. The `LightMediaService` spike was deleted in full.
What remains ours: the service's player needed `setDeviceVolumeControlEnabled`
and `setHandleAudioBecomingNoisy`, and its session a `sessionActivity` — three
additive lines, in `audio-service.patch`, written to be upstreamed. One
behavioural trade accepted: the SDK stops a paused, unheld session after 15
minutes, where the spike held on indefinitely.

### Background downloads

- **Need:** finish downloading an album after the user leaves the app.
- **Now:** a `dataSync` foreground service (`LightTransferService`) held for the
  length of a transfer.
- **Measured:** without it, a backgrounded tool is throttled about 9x on the LP3.
- **Would replace it:** an SDK transfer primitive — fetch this URL to this file,
  keep going in the background. A podcast or photo tool would want the same.
- **Requested upstream:**
  [lightphone/light-sdk#187](https://github.com/lightphone/light-sdk/issues/187)
  (31 Aug 2026) proposes a `background-transfer` capability in detached audio's
  shape, with the throttle measurements attached.
- **Revert:** delete `LightTransferService.kt`, its calls in `Downloader.kt`, and
  the service and permission from the SDK manifest.

### Hardware volume keys

- **Need:** the rocker changes playback volume, with the LightOS volume panel.
- **Fixed in LightOS 572:** the keys reach a side-loaded tool now. Before, the
  token was rejected and the press did nothing.
- **Still wrong:** forwarded keys move the **ringer**, even with music playing.
  LightOS chooses the stream in its own process, where nothing is playing.
- **Nothing on our side can say otherwise:** `DeviceKeyEvent` carries keyCode,
  action and characters — no stream. Setting `volumeControlStream` on the
  foreground activity does not cross the process boundary (tried, 20 Aug 2026).
- **Now:** we keep volume keys away from the server and let them fall through to
  the system, with `volumeControlStream = STREAM_MUSIC` so the stream is right.
  That loses the LightOS volume panel.
- **Would fix it:** route forwarded volume keys to the active media session, or
  carry the foreground tool's `volumeControlStream` in `DeviceKeyEvent`.

### Colour

- **Need:** album art in colour. The LP3's greyscale is a device-wide
  accessibility filter, not the panel — a screenshot comes out in full colour.
- **Now:** `LightDisplayColor` writes `Settings.Secure` to switch the filter off
  while Amp is in the foreground, with a marker file so a crash cannot strand the
  phone in colour. Needs `WRITE_SECURE_SETTINGS`, granted once over adb.
- **Not in a submitted build:** it lives in the debug and release manifest
  overlays, never in `src/main`.
- **Would replace it:** a per-tool exemption requested in `lighttool.toml`. A
  photo, camera or maps tool would want it too.
- **Revert:** delete `display/LightDisplayColor.kt`, its two calls in
  `LightActivity`, the app's toggles, and the permission from the debug manifest.

### Bluetooth

- **Need:** see paired devices, connect and disconnect, and start pairing, from
  the tool's own output screen.
- **Now:** nothing. Amp's Output screen lists "This device" and any network
  speakers it finds, with a Bluetooth row that is permanently disabled and a note
  saying Light handles the routing.
- **Why not in the tool:** `BluetoothManager` needs `getSystemService`, which the
  sandbox blocks. So does handing off — opening the system Bluetooth screen needs
  `Intent` and `startActivity`, both blocked too. There is no route at all, not
  even a bad one.
- **Would replace it:** either would help, smallest first —
  - an SDK call that opens the system Bluetooth screen, or
  - paired devices with connect and disconnect, alongside the audio API.
- **Why it belongs in a music tool:** choosing where sound comes out is part of
  playing it. Today, connecting headphones means leaving Amp for Settings and
  coming back.

### Casting

- **Need:** play to a speaker on the network.
- **Now:** `DlnaCast` — SSDP discovery and SOAP control written by hand, since the
  sandbox blocks the usual libraries.
- **Would replace it:** an output-routing API covering Bluetooth, Cast and UPnP.
- **Revert:** delete `cast/DlnaCast.kt` and the DLNA section of
  `PlaybackController.kt`.

## Answered upstream, not yet adopted

### Detached audio — PR #148: ADOPTED, 31 Aug 2026

`newPlayer(playback = Detached)` + `capabilities = ["detached-audio"]`; the
plugin emits the service and its permissions into the tool manifest. Every one
of our player additions rides the `Player` interface, which the detached
`MediaController` implements. The background-audio section above has what
little remains ours. Still open: whether hardware volume keys route through
the SDK's session (the `LightActivity` pass-through spike stays until the
phone answers).

### Connectivity — PR #166: adopted, gap closed

`LightConnectivity` gives connected/Wi-Fi/metered and an observer. Amp took it
in 0.5.0 (as a verbatim backport, replacing an interface-polling heuristic);
since the 0.1.1 pin it is plain upstream code and the backport patch is gone.
The tool's `Connectivity.kt` remains only as a thin metered-ness wrapper for
the data-mode gates.

## Smaller additions

Already written as patches; see [SDK-PATCHES.md](SDK-PATCHES.md), which is the
authority. None were upstream as of `3df3c24` (0.1.1).

| Gap | Why |
|---|---|
| `popToRoot()` | A tab bar visible on nested screens has to unwind to the root. Without it, tapping a tab three levels deep only goes back one. |
| `onPlaybackError` | Media3 reports failures; the SDK swallowed them. A player that cannot report a failure cannot fall back to a downloaded copy. 0.1.1 grew an `error: StateFlow<LightAudioError?>` — real progress, but it maps the exception away and the fallback logic reads the raw `errorCode` (a bad HTTP status proves the server is *there*). The callback stays until the flow carries the code. |
| Room migrations | `buildDatabase` exposes no builder, so a tool cannot register a migration. We patched in `fallbackToDestructiveMigration`, which means every schema change wipes the user's cache and download index. |
| `replaceRange()` | Reordering by repeated `moveItem` rebuilds the session queue per move; a few hundred moves exhausts memory. |
| `isCurrentItemSeekable` | Whether the stream that arrived can be seeked. Guessing from the requested format is wrong when a server declines to transcode, and it fails silently. |
| `setHandleAudioBecomingNoisy(true)` | One line on the builder. Without it, unplugging headphones leaves music playing out of the speaker. Doing it by hand needs a `BroadcastReceiver`, which the sandbox blocks. |
| Playback state | The player exposes position, duration and `isPlaying`, but not the state behind them. A tool cannot tell a finished queue from a pause, so we infer it from position against duration. 0.1.1 added `availability` (connection lifecycle) and the `error` flow, but `STATE_ENDED` is still not surfaced. |
| `TextInputKeyboardCallback` | `LightEmbeddedLp3Keyboard` is public and usable on a tool's own screen, but the callback that turns keys into edits is `internal`. We copied ~60 lines of it, including surrogate-aware backspace, to put a keyboard under our search results. |
| Splash icon centring | The loading glyph sits ~88px above centre on a 1240px panel; the artwork is off-centre inside its own viewport. |

## Blocked above the API

- **Physical buttons.** The side buttons and dimmer wheel are behind a LightOS
  token trust-gate. A side-loaded tool cannot bind them and no SDK patch changes
  that.
- **The stock Music tool's library.** It lives in `com.lightos` private storage
  and does not appear in MediaStore, so a tool cannot offer to play it. Amp reads
  its own folder, which is why local files go in `Music/Amp`.
