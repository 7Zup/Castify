# Subtitles provided by the site

## Behavior

- **Off by default** at every new video (user rule).
- Sources: text tracks inside the stream (HLS `EXT-X-MEDIA TYPE=SUBTITLES`, DASH) and subtitle
  files the page loads (`.vtt`, `.srt`, `.ass/.ssa`, `.ttml/.dfxp`), side-loaded on the TV.
- Menu, identical on phone and TV (`SubtitleMenu`):
  `Off`, then English tracks, then other languages alphabetically, **names in English**, then
  `Unknown` (numbered `Unknown 1`, `Unknown 2` when several). No flags (user: not needed).
  The site's label is kept only for what it adds: `Français (Forced)` → `French (Forced)`,
  `Español` → `Spanish`. Same visible name twice → `French`, `French 2`.
- **Phone**: `CC` button in the now-playing bar (left of play/pause) and in the remote header.
  White = subtitles shown, gray = off, dimmed = none available. Tap → sheet with the list, ✓ on
  the active one; picking applies at once on the TV. None → `No subtitles available for this video`.
- **TV**: ▼ shows the player controls; the `CC` button opens the `Subtitles` panel on the right. ▲ ▼ move (focus = white
  outline + slight scale), OK picks and closes, Back closes without change. ✓ marks the active
  track. None → `No subtitles available`. While open, ▲ moves instead of toggling diagnostics.
- Phone and TV stay in sync through `/status` (last one to pick wins).
- Burned-in subtitles cannot be removed.

## Design

- Domain (`app.castify.domain.subtitles`): `SubtitleSource` (file seen on the page),
  `SubtitleTrack` (track on the TV), `SubtitleMenu` (order + names), `LanguageName`
  (codes `fr` / `fr-CA` / `fra` / `fre` and names in English or native → English name, JDK
  `Locale`), `SubtitleClassifier` (file recognition + language guessed from the address:
  `movie_fr.vtt`, `movie.eng.srt`, `/en/subs.vtt`, `Spanish.vtt`; HLS subtitle segments such as
  `sub_00012.vtt` are ignored).
- `VideoCatalog` keeps the page's subtitle files and attaches them to every video;
  a `<track srclang label>` element (read by the page script) overrides an address guess.
- Commands: `PlayerCommand.SelectSubtitle(trackId?)`; `OpenSubtitleMenu` is TV-only (▼),
  like `ToggleDiagnostics` (`PlayerCommand.isTvOnly`, never sent by phones).
- Protocol: `PlayRequest.subtitles` (`SubtitleFile`: url, format, language, label);
  `StatusResponse.subtitles` + `selectedSubtitleId`; `/control` action `subtitles` with
  `trackId` (null = off). Unknown formats are dropped, not fatal.
- TV `ExoPlayerAdapter`: files → `MediaItem.SubtitleConfiguration` (fetched with the page
  headers like the video); text tracks disabled at start (`setTrackTypeDisabled(TEXT)`);
  track ids `"<group>:<index>"` from `onTracksChanged`; selection = `TrackSelectionOverride`.
  Rendering: `PlayerView`'s default subtitle view (white text, black outline).

## Limits

- Many sites name files `1.vtt` or play in a cross-origin frame where `<track>` is invisible:
  those show as `Unknown`.
- Subtitles drawn by the site's own JavaScript or delivered as JSON are not detected.
- Every subtitle file of the page is attached to every video of that page.
- Previous movie's subtitles (bug fixed 11/05/2026): single-page sites change movie without
  a reload, so the old files stayed attached. Now (1) a path change in the address
  (`doUpdateVisitedHistory`, query/fragment ignored) resets the page like a reload, and
  (2) for a known language + label only the newest file is sent (`latestPerLanguage`).
  Remaining case: a site switching movie with the **same address** and files of
  **unknown language** → both movies' files can still be listed (`Unknown 1`, `Unknown 2`).

## How to test

- Unit: `SubtitlesTest`, `ProtocolTest`, `PlaybackTest`.
- From the Mac (verified 11/05/2026 on the real TV): play Apple's
  `bipbop_16x9_variant.m3u8` with one side-loaded VTT → 9 tracks listed, none selected; then
  `curl -d '{"action":"subtitles","trackId":"5:0"}' http://<tv>:47800/control` → selected;
  `"trackId":null` → off.
- Manual: see `docs/STATUS.md`.
