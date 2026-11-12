# Change the video quality while it plays

Mockup: `docs/mockups/quality.html` (approved 11/12/2026).

## Behavior

- The qualities are those the video offers on the TV (its video tracks), best first, named
  like the send flow (`1080p`, `720p`…, `QualityLabel`).
- **Phone**: a pill with the playing quality next to CC, top right of the full remote (not in
  the mini bar). Tap → `Quality` sheet, best first (`best` under the first), the playing one
  checked; a tap switches the TV and closes the sheet.
- **TV**: an `HD` button between CC and Episodes, label `Quality · 1080p` when focused. OK →
  menu on the right like subtitles: ▲ ▼ move, OK picks and closes, Back closes.
- One quality only: the pill is greyed, the sheet / menu says `Only one quality for this video`.
- Switching does not restart the video.
- **Episodes** (phone, TV, Next, Auto next): the best quality of the new episode is picked by
  default (user rule), not the quality of the previous one.

## Design

- `PlaybackStats.qualities` / `qualityHeight` (ExoPlayer video tracks / playing format),
  copied to `NowPlaying`; `NowPlaying.currentQuality` maps the playing height to a listed one.
- `PlayerCommand.SelectQuality(height)` → `Player.selectQuality`: the same min = max height lock
  as a quality picked before sending. Wire: `/control {"action":"quality","height":720}`,
  `/status` `qualities` + `qualityHeight`.
- `EpisodePlayer` sends other episodes with the first (best) quality of their detected video.

## How to test

1. Cast a video that offers several qualities; phone remote → the pill → pick 480p: the TV
   switches without restarting, the pill says `480p`.
2. TV: ▼, the HD button, pick 1080p.
3. Change episode: it starts in its best quality.
