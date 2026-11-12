# Status

_Last updated: 11/12/2026_

## Done (all merged into `main`)

- **v0** browser + stream detection + Castify TV app (ExoPlayer), diagnostics.
- **v0.1** video list: thumbnail, duration, quality picker (names from width, no `Auto`), ads
  and videos without duration hidden, MP4 header probe for players in iframes.
- **v0.2** Cast button / connection, now-playing bar with buffer, full remote (one line,
  ±10 s bubble), media notification + lock screen, several phones, replace confirmation,
  TV search spinner + refresh, Netflix theme on both apps, TV welcome screen.
- **v0.3** site subtitles: off by default, phone `CC` sheet, TV ▼ menu, English first.
- Fix: previous movie's subtitles sent with a new one (single-page sites).
- TV time bar on pause and for 3 s after a seek, with title (cleaned page title) and the
  cumulative `+10 s` bubble.
- ADRs 0001 (Android), 0002 (own TV app), 0003 (other TVs later).
- TV player controls (Netflix style, compact panel), OK pauses and shows them, Back hides
  overlays first and asks a second Back before stopping (PR #10).
- Mobile browser (PR #11): tabs (Chrome style, only the active one loaded, max 100, restored
  at launch), favorites (★, name only), history of every visited page (max 1000, search),
  home screen, tight top bar (Cast, tabs counter, ⋮). Spec `docs/features/browser.md`, mockup
  `docs/mockups/mobile-browser.html`. Validated by the user on the phone.
- 121 unit tests green. Both apps installed on the user's devices.

## Repo

- Public since 11/06/2026, PolyForm Noncommercial 1.0.0 license, history restarted at one
  commit (PR numbers mentioned in these docs refer to the earlier private repo). Nothing
  personal in tracked files: `.kiro/steering/privacy.md`.

## Devices

- Models, serials, IPs and TV names: gitignored `.kiro/steering/local.md` (never in the
  repo, see `.kiro/steering/privacy.md`).
- Phone (OnePlus, USB) + two TVs: a Chromecast with Google TV HD (the main one) and a
  Chromecast with Google TV 4K, both Android TV 14 with Wireless debugging paired (connect
  port changes, see `docs/setup-devices.md`). The 4K still answers the old Cast
  `:8008/setup/eureka_info`, which once misled the agent.
- Do not reinstall the TV app while the user watches (see `.kiro/steering/tech.md`).

## In progress

- **User report of 11/12/2026**: merged (PR #8), released in **v0.5.3** (11/12/2026); both
  apps offer it at their next launch.
  Checked by the user on the phone: video paused on cast, pulsing connect icon, now-playing bar
  ⌃ / remote ⌄ with the grow animation, whole-strip highlight. Contents:
  - phone video paused at send start + after success, iframes included (`PageMedia`);
  - connecting state: connected only once the TV answers, `<TV> isn't responding…` otherwise,
    grey icon when the TV stops answering, picker `Not responding` (status probe every 3 s),
    session follows a TV whose IP changed;
  - video icon + red badge in the top bar instead of the floating `N videos found` button,
    which hid the sites' own player buttons (root cause of "can't navigate");
  - tab back/forward history kept across restarts (`TabHistoryStore`): a site's own ← works;
  - now-playing bar ⌃ / remote ⌄ (no drag, after trying a handle and a finger-following
    resize), the mini player grows into the remote, the remote takes every touch;
  - debug builds allow `chrome://inspect` on the WebView.
  Not checked on a device yet: `Not responding` / IP follow, tab history after a restart, TV
  welcome step 3 text (TV not reinstalled).
- Site player panels + fullscreen (PR #10), released in **v0.5.4**: Settings / Subtitles
  panels opened collapsed (CSS `vh` = 0, WebView was wrap-content), fullscreen button did
  nothing (no `onShowCustomView`). Panels checked by the user. PiP unavailable in a WebView.
- **TV shows v1** on `feat/tv-shows` (not committed yet), spec `docs/features/episodes.md`:
  episode recognized from the address, list from TVmaze (via Wikidata), phone remote
  Episodes section + Auto next, TV Episodes button / panel, `Next` 30 s before the end,
  10 s Auto next countdown, loading / failed card; other episodes loaded in a hidden WebView.
  A phone joining a running episode gets the series from the TV status; TV requests are
  claimed by one phone. Fixed: crash when scrolling the remote (NaN thumbnail scale).
  131 tests green. Phone + HD Chromecast installed 11/12/2026; the user reports it works
  (Next, Auto next, episode change all checked). Then: TV episode pictures (Coil added to the
  TV app) and Auto next row moved up (was cut at the bottom): built, not installed yet (TV
  playing). The 4K Chromecast is not updated.
- **v0.6.0 released** (11/12/2026): TV shows (PR #12), What's new + TV home update check +
  quality switching + address bar fixes (PR #14). Checked by the user on the phone and the HD
  Chromecast. The 4K Chromecast gets it from its home screen.
- Declined 11/12/2026: TV volume from the phone ("tout ou rien"), findings in ROADMAP.
- **In-app update + version label** (branch `feat/app-update`, spec
  `docs/features/app-update.md`, mockup `docs/mockups/app-update.html`, publishing
  `docs/releasing.md`): silent check of the GitHub release at launch, `Update` / `Not now`
  dialog, download + Android install, `Retry` on failure, TV welcome screen only; `v0.5.0`
  bottom right of both home screens. Merged (PR #2). Releases `v0.5.0` and test `v0.5.1`
  published 11/06/2026. v0.5.0 installed by adb on the phone (label OK, no dialog on
  v0.5.0) and the 4K Chromecast; the HD Chromecast still on the old build (was playing a
  movie). To check by the user: the v0.5.1 update flow on the phone and the 4K TV; then
  install v0.5.0 by adb on the HD Chromecast when idle.
  The user updated to v0.5.1 (11/06/2026); on the phone Android killed the app when
  "Install unknown apps" was switched on (download twice). Fix: branch
  `fix/update-permission-first` (permission screen before the download, resumes by itself).
- **Offline / error screens** (branch `feat/offline-errors`, mockup `docs/mockups/offline-errors.html`):
  phone P1–P4 (no internet, site unreachable, home banner, TV unreachable snackbar), TV T1–T2
  (`Connection lost` / `This video can't be played`, Retry / Home) and welcome states Standby /
  Connected / Offline with green checks. Retry only, nothing automatic. Phone + the HD Chromecast
  installed; Standby and Connected seen on the HD Chromecast by the agent; error screens and the phone
  screens not checked on a device yet; the 4K Chromecast not updated (adb not connected).
- **Data rules** (merged, PR #17): the phone app is bound to the Wi-Fi; the TV caps its
  download at 3× the quality (min 15 Mbps). The phone blocks nothing in the page (a first try
  blocked video data: rejected by the user). The HD Chromecast has it; the 4K Chromecast not yet.
- Fixes from the user's bug report of 11/05/2026, all merged: page silent in the background
  and TV media notification (#12), UDP discovery + mDNS retries (#13), tapped new-tab links
  open in the current tab (#14), browser tweaks + new home screen with the Castify logo (#15,
  mockup `docs/mockups/home-screen-final.html`), TV picker lists every Cast device with
  `Ready` / gray `App not launched` (#16, mockup `docs/mockups/tv-picker-not-launched.html`).
  User checks on the phone for #14, #15 and #16 still to come.
- Waiting on the user: the series site URL (its episode tooltip is unreadable in the app).
- Parked: audio language switch (ROADMAP).
- Declined by the user: TV app listening in the background and opening by itself (it must
  be open on the TV screen to be found).

## Next step

- **TV shows** (episodes + seasons on phone and TV, `Next` 30 s before the end, `Auto next`):
  v1 built on `feat/tv-shows` (see In progress): next = the user's check on the phone + TV
  (install the TV app first), then PR + release.
  Scope and approach in `docs/ROADMAP.md` ("Next: TV shows"), mockup
  `docs/mockups/episodes.html` (approved).

## Parked by the user (do not build without asking)

- Playback retries (longer backoff on HTTP 503 / network errors, auto-restart at the last
  position, `Reconnecting…` on the TV): "we'll see if the bug comes back". Context: one real
  error seen, HTTP 503 from the site's HLS proxy at 14:46 on 11/05/2026; the other
  "crashes" were the agent's reinstalls.

## Known issues

- Burned-in ads/watermarks (e.g. 1XBET) cannot be removed; ads parked.
- Detection relies mostly on URL extensions; YouTube not supported (has its own Cast).
- Subtitles of unknown language can still mix movies on sites that switch movie without
  changing the address.
- No authentication on the TV LAN endpoints (user's choice).
- The TV seek bubble was not caught on a screenshot (lasts 1.2 s); same logic as the phone.
