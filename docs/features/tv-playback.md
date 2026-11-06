# TV playback (phone → TV)

## Behavior

**Phone**
- After picking a stream, the app lists Castify TVs found on the Wi-Fi (`Looking for TVs…`
  while searching, `No Castify TV found` with a hint if none). With one TV it sends directly.
- Result as a snackbar: `Playing on <TV>` or `Could not reach <TV>: <reason>`.

**TV (Castify TV app)**
- Welcome screen (layout "B", mockup `docs/mockups/offline-errors.html` T3–T5): left panel =
  logo, title, pill with a dot + TV name + state; right = a title and 3 steps. Each state
  checks (green ✓, user choice) the steps the TV really knows are done:
  | State | Dot / label | Title | Steps |
  | --- | --- | --- | --- |
  | **Standby** (on the network, no phone) | white, pulsing / `Standby` | `Ready to cast` · `Watch a video on this TV` | ✓1, 2, 3 |
  | **Connected** (a phone called the TV in the last 6 s: `/status`, `/control` or `/play`) | green / `Connected` | `Ready to cast` · `Pick a video on your phone` | ✓1, ✓2, 3 |
  | **Offline** (no network) | red / `Offline` | `Not connected` · `Connect this TV to Wi-Fi` | 1, then 2 and 3 dimmed |
  Steps: 1. `Connect this TV to Wi-Fi` · `Settings → Network & Internet, same Wi-Fi as your
  phone`; 2. `Open Castify on your phone, tap <Cast icon> and pick <TV>` · `Same Wi-Fi as this
  TV`; 3. `Find a video, tap “Videos found”` · `Once the video plays, you can lock your phone
  and enjoy`. Rule `TvPresence.of` (domain); it follows reality by itself (a status, not a retry).
  No remote key reminder (user: overkill). The app must be open on the TV.
- On a play request the video starts full screen; a spinner shows while buffering.
- Remote (no media keys on the Google TV remote):

  | Key | Action |
  | --- | --- |
  Controls hidden (shortcuts):

  | Key | Action |
  | --- | --- |
  | OK | Pause **and show the player controls** (focus on Play/Pause, so OK again resumes) |
  | Left / Right | −10 s / +10 s (hold to repeat) |
  | Up | Show / hide diagnostics (`diagnostics.md`) |
  | Down | **Show the player controls** |
  | Back | Something on screen (time bar, bubble, diagnostics) → hide it, video goes on. Nothing on screen → `Press Back again to stop the video` for 3 s; a second Back in that window stops (user: an accidental Back must not lose the video). On the welcome screen: exit the app |

  **Player controls** (Netflix style, user request 11/05/2026; compact layout "B": everything
  in a dark rounded panel `#0B0B0B` at 88 % at the bottom, label shown only under the
  focused button; the pause/seek time bar uses the same panel): title, time bar, then buttons
  `⟲10` · `Play/Pause` · `10⟳` on the left and `CC` on the right (`Subtitles · On/Off`,
  `No subtitles`). No Stop button: Back, Home or leaving the app stop the video (`onStop`).
  - Focus starts on Play/Pause; ◀ ▶ move between buttons; OK activates; focused button =
    white filled circle + slight scale.
  - ▲ puts the focus on the time bar (white ring on the thumb): ◀ ▶ then seek ±10 s, OK
    pauses; ▼ goes back to the buttons. Live / unknown duration: no ±10 s, bar not focusable.
  - `CC` opens the subtitle menu (`subtitles.md`); closing it returns to the controls.
  - Back hides the controls (never stops the video). A pause bar hidden with Back comes back
    at the next pause. They auto-hide after 5 s without a key press, never while
    paused or while the subtitle menu is open.
  - Room is kept in the row for episode / season buttons (next step, see ROADMAP).

- **Time bar** (bottom, dark scrim), choices made by the user on 11/05/2026:
  - shown **while paused** (with `❚❚ Paused`) and for **3 s after any seek** (TV ◀ / ▶ or a
    phone seek);
  - title of the video if known (cleaned page title, `PageTitle`), progress bar (red =
    played, light gray = buffered, like the phone), elapsed left, **total duration** right;
    live → `Live`, no bar; unknown duration → elapsed only;
  - ±10 s presses add up in a center bubble `+10 s`, `+20 s`… (reset 1.2 s after the last
    press or when the direction changes), same rule as the phone remote.
- Playback error (mockup T1 / T2, rule `PlaybackFailure.of`):
  - network lost (Media3 connection failed / timeout): `Connection lost` · `The TV lost its
    internet connection. Check the Wi-Fi, then try again.`;
  - anything else (HTTP error, unsupported format): `This video can't be played` · `The website
    refused to send the video. Try again, or send it again from your phone.`;
  - buttons **Retry** (focused) and **Home**, ◀ ▶ move, OK presses, Back = Home. Retry plays the
    same stream again from the last position, with the same subtitle; Home stops and shows the
    welcome screen. Nothing retries by itself (user rule). Bottom right, small: title, `stopped
    at mm:ss`, HTTP status or Media3 error code.
- Leaving the app (Home) stops playback and the LAN server.

## Design

- Discovery: the TV registers an mDNS service `_castify._tcp` named `<TV name> · Castify`
  (`LanCastReceiver`); the phone browses it with `NsdTvDiscovery` and shows `<TV name>`.
  Reusing the bare TV name fails on Android TV 14 (`NameConflictException`).
  Failed registrations are retried (5 attempts).
- UDP discovery, next to mDNS (Android 9/10 mDNS resolves can stay stuck until the app is
  killed, user bug 11/05/2026): the phone (`UdpTvDiscovery`) broadcasts
  `CASTIFY_DISCOVER_1` to UDP 47801 every 1.5 s while the TV list is open, on the Wi-Fi
  network explicitly; the TV answers `{"app":"castify-tv","name","port"}` to the sender.
  A TV that stops answering leaves the list after 6 s. `ObserveTvDevices` merges both
  methods by TV name (UDP address wins). mDNS resolves now retry 3 times and time out
  after 5 s. Both apps hold a Wi-Fi multicast lock while searching / listening.
- Phone → TV HTTP goes through the Wi-Fi network (`WifiNetwork.open`). The whole phone app
  is also bound to the Wi-Fi while one is up (`WifiNetwork.keepAppOnWifi`, user rule
  "always force the Wi-Fi"): pages and inspection never use mobile data then.
- Protocol (`:core:protocol`), port 47800:
  - `POST /play` with a `PlayRequest` JSON (`url`, `type`, `pageUrl`, `headers`, `title`,
    `preferredHeight`, `thumbnailUrl`, `version`). 200 `{}` or 400 `{"error"}`;
  - `GET /status` → `StatusResponse` (state, position, duration, live, thumbnail);
  - `POST /control` with `ControlRequest` (`toggle`, `seekBy`, `seekTo`, `stop`), applied only
    while a video is on screen. See `remote-control.md`.
- Fixed quality: the phone always sends the master playlist; with `preferredHeight` the TV
  locks video tracks to that height (`DefaultTrackSelector` min = max height). Audio tracks
  are untouched, so separate HLS audio renditions keep working.
- TV server: Ktor CIO, bound to port 47800 while the activity is started.
- Player: `ExoPlayerAdapter` builds one ExoPlayer per stream with the captured headers
  (`User-Agent` set separately, `Range` dropped) and the domain `BufferPolicy.V0`
  (min 60 s, max 120 s, start 2.5 s, resume after stall 10 s). The buffer is set in
  duration; Media3's default byte target (not changed) only matters between 60 and 120 s.
- **Download rate cap** (user rule, 11/06/2026, `DataRules.maxRateBps`): 3× the playing
  quality's bitrate, at least 15 Mbps (≈ 18 Mbps for 1080p at 6 Mbps; 15 Mbps until the
  bitrate is known). `ThrottledDataSource` + `RateLimiter` slow the player's network reads;
  the cap follows the quality. Shown as `Rate cap` in the diagnostics overlay (the
  `Bandwidth` estimate now stays at or under it).
- Key mapping is a domain rule: `RemoteControl.commandFor`, applied by `HandlePlayerCommand`.

## Limits

- **No authentication (v0)**: any device on the Wi-Fi can make the TV play a URL. Pairing
  code planned for v1.
- Fixed port 47800: if it is taken, the server fails to start.
- **No authentication** on `/status` and `/control` either (user's choice).

## How to test

- Unit: `./gradlew :core:domain:test :core:protocol:test`.
- Manual: see `docs/STATUS.md` → "Manual check".
