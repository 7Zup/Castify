# Clean architecture (non-negotiable)

## Dependency rule

Dependencies point **inward only**:

```
presentation  ──►  domain  ◄──  data / infrastructure
```

- **domain**: pure language code (no Android, no Cast SDK, no WebView, no HTTP lib).
  Holds entities (`MediaStream`, `CastDevice`, `CastSession`, `BufferPolicy`…),
  use cases (one public operation each, e.g. `DetectStreams`, `CastStream`) and
  **ports** (interfaces the outside world implements: `StreamDetector`,
  `CastGateway`, `MediaProxy`…).
- **data / infrastructure**: adapters implementing the ports (WebView sniffer,
  NSD discovery, LAN client/server, ExoPlayer, disk cache, ad blocklist, storage).
- **presentation**: UI + view models. Calls use cases only, never an adapter directly.
- Two apps (phone, TV) share the same pure core. Wire formats live in
  `:core:protocol`, never in `:core:domain`.

Wiring (dependency injection) happens in one place at each app edge.

## Rules

- Enforce the boundaries with Gradle modules (`:core:domain`, `:core:protocol`,
  `:phone:data`, `:phone:app`, `:tv:data`, `:tv:app`, see `docs/ARCHITECTURE.md`),
  not just folders, so a violation fails the build.
- The domain is unit-tested without any device or emulator.
- No business rule in the UI or in an adapter (e.g. "buffer everything after 1 min
  of watching" is a domain policy, the proxy only executes it).
- New external SDK = new adapter behind a port. Never leak SDK types into the domain.
- Prefer small, explicit code over clever abstractions. No speculative layers.
