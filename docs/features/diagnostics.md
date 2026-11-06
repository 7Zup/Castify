# Diagnostics overlay (TV)

## Behavior

Press **Up** during playback to show/hide a panel (top left):

| Line | Meaning |
| --- | --- |
| State | buffering / playing / paused / ended / error |
| Position | current / total (`live / unknown` when no duration) |
| Buffered ahead | seconds downloaded ahead of the playhead |
| Bandwidth | Media3 network estimate |
| Rebuffers | stalls after playback had started (seeks excluded) |
| Format | HLS/DASH/PROGRESSIVE · resolution · video bitrate |

## Why

Measure where buffering comes from before building the smart buffer (v2): if `Buffered
ahead` stays near its max and rebuffers stay at 0, the v0 policy is enough; if bandwidth
is below the video bitrate, the CDN/Wi-Fi is the bottleneck.

## Design

`ExoPlayerAdapter` fills `PlaybackStats` (domain): a 500 ms ticker for position/buffer,
`AnalyticsListener` for bandwidth and format, `Player.Listener` for state, errors and the
rebuffer count.
