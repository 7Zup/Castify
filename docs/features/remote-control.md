# Connection to a TV and remote control (phone)

## Behavior

**Cast button** (top right of the address bar)
- Gray `Cast` icon: not connected. Tap → TV list (`Play on`) → pick a `Ready` TV → connected.
- Red `CastConnected` icon: connected. Tap → `Connected to <TV>` → `Disconnect` / `Cancel`.
- The connection lasts while the app process lives (not remembered after the app is killed).

**Sending a video**
- Connected: the video goes to that TV.
- Not connected: the `Play on` list always opens, even with a single TV (user rule: the
  TV is never picked automatically); the picked TV becomes the connected one.
  Already connected: never asked again.

**TV list** (`Play on`, from the Cast button or the send flow)
- The search runs as long as the list is open. No TV yet → spinner
  `Searching for TVs on your Wi-Fi…` + hint (never an empty "not found").
- Refresh button (top right) really restarts the network search (UDP broadcast + mDNS
  browsing stopped and started again), e.g. after the TV was switched off and on. The list
  is cleared and the `Searching…` spinner stays at least 2 s before the results show (no
  half-second flash).
- The search heals by itself after a Wi-Fi drop: no need to kill the app.
- Every line has a status dot (mockup option B, `docs/mockups/tv-picker-not-launched.html`):
  green `Ready` = Castify TV answers, tap plays; gray `App not launched` = any other Google
  Cast device on the Wi-Fi (Google TV with the app closed, plain Chromecast, Cast smart TV,
  speaker), dimmed, tap does nothing. A line turns `Ready` by itself when the app opens on
  the TV, and back to gray a few seconds after it closes. Ready first, then the others,
  each by name. Speaker icon for audio-only devices; speaker groups hidden. When only
  `App not launched` devices are found, no hint text under the list.
- Limit: `App not launched` cannot tell "installed but closed" from "not installed", and
  is also shown on devices that can never run the app (speakers, plain Chromecasts).
- A video is already loaded on the TV (by this phone or another one):
  `Replace the video playing on <TV>?` → `Replace` / `Cancel`.

**Now playing bar** (bottom, while the connected TV plays/pauses/loads a video)
- One line: thumbnail, TV name, `elapsed / duration` (`Loading…`, `Live`, `Not reachable`),
  play/pause, stop. Below it, a draggable progress bar (seeks on release; hidden for live).
- Progress bars (bar and remote) show the TV buffer YouTube-style: dark = not loaded,
  light gray = downloaded by the TV, red = played. Not in the notification (system media
  controls cannot draw it; user agreed).
- Tap the thumbnail or the text → full-screen remote (the only place with ±10 s, by user choice).

**Remote** (full screen, Back or ⌄ closes it)
- Thumbnail (spinner while loading), progress slider with elapsed and duration, then one
  line of controls: `−10 s`, play/pause, `Stop`, `+10 s`. Live: no slider, `Live`, no ±10 s.
- Each ±10 s press shows a bubble over the thumbnail that adds up while tapping (`+10 s`,
  `+20 s`, `+30 s`…; same for `−`), reset 1.2 s after the last press or when the direction changes.
- TV unreachable: `<TV> is not reachable` + `Retry`; the connection is kept. Elsewhere in the
  app (browser), a snackbar `Can't reach <TV>` · `Retry` stays until the TV answers again
  (mockup P4).
- Closes by itself when nothing plays any more (end, Stop, Back on the TV remote).

**Background / lock screen**
- Media notification `Castify · <TV>` with thumbnail, −10 s, play/pause, +10 s.
- Android 13+: the app asks for the notification permission when connecting.

**Several phones**
- Every phone connected to the same TV sees what plays and can control it. Last press wins.
- No pairing code (user's choice): anyone on the Wi-Fi can control the TV.

## Design

- Domain `TvSession` (`:core:domain`): connected device, `NowPlaying`, reachability; polls
  `TvClient.status` every 1 s while a video is loaded, every 3 s otherwise; `send` checks the
  status and returns `NeedsConfirmation` if a video is loaded; `control` forwards a
  `PlayerCommand` (never `ToggleDiagnostics`).
- `NowPlaying.bufferedPositionMs` = TV position + buffered ahead, sent in `/status`.
- TV list refresh: `BrowserViewModel.devices` is `flatMapLatest` over a run counter;
  each run re-collects `ObserveNearbyTvs` and starts with null.
- `ObserveNearbyTvs` (domain) combines `ObserveTvDevices` (UDP + mDNS `_castify._tcp`) with
  the `CastDeviceScanner` port; `merge` matches a Cast device to a Castify TV by address,
  else by name (the TV app advertises the device name the Cast service uses as `fn`),
  hides groups and orders the list. A failing Cast scan never hides the Castify TVs.
- `NsdCastDeviceScanner` (`:phone:data`) browses `_googlecast._tcp` and reads its TXT
  record: `fn` name, `md` model (`Google Cast Group`), `ca` capability bits (1 = video
  out, 32 = group). Read-only: nothing is sent to the devices.
- `NsdResolver` is shared by both mDNS browsers: one resolve at a time (Android < 14),
  Castify TVs first, 5 s timeout and 3 retries.
- Protocol: `GET /status` → `StatusResponse`; `POST /control` → `ControlRequest`
  (`toggle`, `seekBy`, `seekTo`, `stop`); `PlayRequest.thumbnailUrl` so other phones see it.
  `NowPlaying.thumbnailHeaders` carries only `Referer` / `User-Agent` (no cookies).
- TV: `LanCastReceiver` serves both routes; phone commands go through `HandlePlayerCommand`
  like the TV remote, only while a video is on screen.
- Notification: `RemoteControlService` (Media3 `MediaSessionService`) with a `RemoteTvPlayer`
  (`SimpleBasePlayer`) mirroring `TvSession`. The session is added with `addSession` in
  `onCreate` (no controller binds to the service; without it there was no notification and
  Android stopped the service 1 min after the app left the screen). ±10 s are `COMMAND_SEEK_BACK/FORWARD` buttons;
  the position they compute is sent as `SeekTo`. Started by `AppContainer` when the connected
  TV starts playing, stopped on disconnect.

## Limits

- Polling continues in the background while connected (light: one small HTTP call every 1–3 s).
- If the app is in the background when another phone starts a video, Android may refuse to
  start the notification; it appears when the app is next opened.
- Notification artwork is loaded without the page headers: some thumbnails may be missing there.

## How to test

- Unit: `TvSessionTest`, `ProtocolTest`.
- Routes from the Mac: see `.kiro/steering/tech.md` (curl smoke test), plus
  `curl http://<tv>:47800/status` and `curl -d '{"action":"toggle"}' http://<tv>:47800/control`.
- Manual: `docs/STATUS.md`.
