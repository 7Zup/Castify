# 0002 – TV side: native Android TV app instead of a Cast web receiver

_Status: accepted (11/05/2026)_

## Context

The user's TV device is a **Chromecast with Google TV (HD)**: it runs Android TV apps,
has ~1.5 GB RAM, a remote, and allows sideloading via `adb` over Wi-Fi.
The core problem to solve is buffering. A Cast web receiver plays in a constrained
browser with a small media buffer and requires a Cast developer account plus hosting.

## Options

1. **Cast default receiver**: zero control over buffering or request headers.
2. **Custom Cast web receiver (Shaka)**: some buffer control, still browser-limited,
   5 USD account, HTTPS hosting, headers only via a phone-side proxy.
3. **Native TV app (Media3 ExoPlayer) + own LAN protocol**: the phone discovers the TV
   app (mDNS/NSD), sends the stream URL with its headers/cookies; the TV fetches the
   media itself with those headers, with a configurable `LoadControl` and a disk
   cache. **Chosen.**
4. Native TV app driven through Cast Connect: adds Google registration and
   Play-Store-oriented constraints for no gain in a personal app.

## Decision

Option 3.

## Consequences

- Two Android apps sharing the pure `:domain`: a phone app (browser, detection,
  sender) and a TV app (receiver, player, buffering).
- Smart buffering moves to the TV (ExoPlayer buffer + disk cache); the phone does not
  need to stay awake as a relay in the normal case.
- No Google Cast SDK, no developer account, no web hosting.
- The app only works with Google TV devices where Castify TV is installed, not with
  plain Chromecasts.
- The TV app exposes a LAN endpoint: it must be restricted (pairing code from v1).
- Phone-side relay remains a fallback if a site binds tokens to the requesting
  device (to be confirmed by the v0 diagnostics).
