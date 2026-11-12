# Roadmap

| Version | Scope | Goal |
| --- | --- | --- |
| **v0 – Spike** (coded, device test pending) | Phone: in-app browser + stream detection + send to TV + popup blocking. TV: Castify TV app plays the stream (ExoPlayer, headers/cookies) + diagnostic overlay | Prove the chain end to end, measure buffering |
| **v0.1** (coded, device test pending) | Video list: thumbnail, duration, quality picker, no URL, ads hidden; → Go button | Pick the right video and quality at a glance |
| **v0.2** (coded, device test pending) | Cast button (connect/disconnect), now-playing bar, full remote, media notification + lock screen, several phones on one TV, replace confirmation, Netflix theme on the phone | Control the TV from any phone |
| **v0.3** (coded, device test pending) | Site subtitles: off by default, choose on phone (`CC`) or TV (▼), English first then alphabetical, Unknown last | Watch with subtitles |
| **Mobile browser** (done, PR #11) | Tabs (Chrome style, only the active one loaded, max 100), favorites, history of every visited page (max 1000, search), home screen, ⋮ menu (Forward, Reload, ★) | A real browser for daily use |
| **v1** | Redirect blocking | Daily use, better than Web Video Caster |
| **v2** | Smart buffering on the TV (policy in the domain, disk cache) | No more buffering |
| **v3** | More subtitles: local file → OpenSubtitles → automatic (Whisper); remember last language; style; offset | Feature parity with WVC's best part |
| **Universal mode** (after v0.2) | Phone relay + Google Cast + DLNA adapters, receiver kind in the TV picker (ADR 0003). Already done: the `_googlecast._tcp` scan (`NsdCastDeviceScanner`), shown as `App not launched` today. Data rule (user, 11/06/2026): here the phone buffers, only once the video is sent, only over the Wi-Fi, capped like the TV (`DataRules.maxRateBps`: 3× the quality, min 15 Mbps) | Works at a friend's place without the Castify TV app |
| v4+ | Advanced ad blocking, favorites, queue, phone relay fallback | Comfort |

## TV shows (episodes + next episode), "hyper important" (user, 11/05/2026)

v1 built on `feat/tv-shows` (11/12/2026), spec `docs/features/episodes.md`.

Comes right after the TV player controls: episode / season buttons join the controls row.
Answers already given, to reuse at kickoff:

- Episode list shown on **both** phone and TV.
- `Next` button appears **30 s before the end**, only when the next episode is known; OK
  plays it. **`Auto next` checkbox** (user, 11/12/2026): off by default, on both phone and TV
  (one shared setting); on = the next episode starts by itself at the end.
- End of a season → episode 1 of the next season.
- Change episode **and season** from the phone (full remote) and the TV (`Episodes` button).
- Approach (agreed 11/12/2026): episodes recognized from addresses like
  `…/tv/<id>/<season>/<episode>` (page or embedded player), episode list from TVmaze (no
  key, credit shown), the phone loads the target episode hidden and sends its video.
  Mockup `docs/mockups/episodes.html`, approved 11/12/2026 (with a 10 s countdown when
  `Auto next` is on, Back cancels it).
- Nice to have: link guessing on sites without that address pattern, `Previous`, resume,
  watched marks.

## Nice-to-have backlog
- **TV picker**: open the Castify TV app from the phone (Android TV Remote protocol, pairing code once); replace the `App not launched` text by an icon only; tell "installed but closed" from "not installed".

- **Continue watching** on the home screen (last videos cast, thumbnail + progress, tap to
  reopen; "Resume" at the saved position would need a start position in `/play`). Mocked in
  `docs/mockups/home-screen-options.html` (option A); dropped by the user on 11/05/2026:
  "c'est du nice to have, pas besoin du tout".

- **TV volume from the phone**: declined by the user on 11/12/2026 ("tout ou rien"): only the
  real TV volume 0–100 would do, and it can't be guaranteed. Findings if it comes back: the HD
  Chromecast gets no HDMI-CEC from its TV (its remote uses IR), so `AudioManager` can only
  change the Chromecast's own output; the smart TV answers UPnP `RenderingControl`
  (GetVolume/SetVolume 0–100) on the Wi-Fi, which needs the user to say which TV hosts which
  Chromecast, and only works with TVs that expose it.

- **Audio language** (user, 11/05/2026: "on viendra la modifier plus tard, mais garde ça en
  tête"): a 1080p stream played in Russian and could not be switched. Today there is no
  audio track listing or selection anywhere (TV, phone, protocol; the HLS parser ignores
  `#EXT-X-MEDIA TYPE=AUDIO`). To scope with the user when they ask.

- **Next (user, 11/12/2026, after TV shows)**: the TV checks for updates each time its home
  screen shows (not only at launch: on Chromecast the app rarely really closes); a short,
  friendly changelog pop-up with `OK` after an update; maybe every changelog in one place.
  Mockups first.

- **In-app update, later** (scoped 11/06/2026, v1 built): the phone pushes the update to the
  TV; release notes in the dialog; smaller APKs (minified release build, the TV debug APK is
  ~85 MB); a "Check for updates" button in a settings screen.

- Smart buffer policy: short buffer at start; after 1 min of watching, prefetch
  aggressively up to a cap (proposal: 1 GB of disk cache, configurable); clean up
  afterwards.
- Ad blocking beyond the DNS: domain blocklist in the WebView.
- Phone relay fallback for sites whose tokens are bound to the requesting device.
- Favorites, queue.
- `Auto` quality (adaptive) in the quality picker: declined for now (some streams jump a
  few seconds when switching quality).
- Thumbnail extracted from the video itself when the site provides none.
- Ad detection beyond known ad hosts.
- **Settings screen** (user: "on mettra ça un jour dans les options"): first candidate is a
  minimum duration filter to hide short videos (tutorials, trailers); threshold chosen by
  the user.
- Video language: dropped (rarely available).
- Burned-in watermarks (e.g. 1XBET logo): optional mask or slight zoom on the TV,
  position chosen by the user. To discuss.
