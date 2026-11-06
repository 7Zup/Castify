# 0003 – Other TVs (Chromecast, DLNA) later, behind the same ports

_Status: accepted (11/05/2026)_

## Context

The Castify TV app only runs on Google TV / Android TV devices. At a friend's place with a
plain Chromecast or a Samsung/LG smart TV, Castify would not work. Web Video Caster
supports those through Google Cast and DLNA.

## Decision

Keep the Castify TV app as the primary mode (best buffering, phone can sleep). Add other
receivers **later**, as new adapters behind the existing phone ports (`TvDiscovery`,
`TvClient`), so detection, video list, remote control and notification are reused:

1. phone-side relay (local HTTP proxy adding the page headers/cookies), required by both:
2. Google Cast adapter (default media receiver);
3. DLNA/UPnP adapter.

The TV picker would show the receiver kind (`Castify TV`, `Chromecast`, `Smart TV`).

## Consequences

- No pivot now; v0.2 work is kept as is.
- `TvDevice` will need a `kind`; `TvClient` calls dispatch per kind.
- Relay mode has WVC's limits: the phone must stay awake and serve every byte, TV-side
  buffer is not ours. Documented to the user as "works everywhere, less smooth".
