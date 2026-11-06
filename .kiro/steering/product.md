# Product: Castify

## One-sentence request (from the user, for themselves)

Send the video stream playing on a website (on the phone) to a Chromecast, without
mirroring the screen, and without the buffering Web Video Caster suffers from.

## Context

- The streaming sites used have no native Cast button.
- Web Video Caster (WVC) works but has buffered heavily for ~2 years, while the same
  video plays fine on the phone/computer.
- The user's phone uses an ad-blocking DNS.
- Phone: Android. Platform decision: `docs/decisions/0001-platform.md`.
- TV device: **Chromecast with Google TV (HD)**, white, 1080p, with remote; runs
  Android TV apps (~1.5 GB RAM). Its small remote has **no play/pause or seek keys**:
  only D-pad + center OK, back, home, Assistant, YouTube, Netflix, volume. Every TV
  interaction must work with D-pad + OK + back. Castify has its own TV app instead of a Cast
  receiver: `docs/decisions/0002-tv-receiver.md`.

## Priorities

1. Detect the video stream in a page and cast it (v1 core).
2. Smooth playback: smarter / bigger buffering.
3. No ads / popups.
4. Subtitles (file, online search, then automatic).

See `docs/ROADMAP.md` for the version split. Stack decisions live in
`docs/decisions/`.
