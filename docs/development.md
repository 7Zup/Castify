# Development

Two Android apps: the **phone** app is a browser that detects the video stream playing in
a website; the **TV** app (Chromecast with Google TV) plays it with ExoPlayer, using the
headers/cookies captured on the phone. No screen mirroring, no Google Cast.

- Status: [`STATUS.md`](STATUS.md)
- Roadmap: [`ROADMAP.md`](ROADMAP.md)
- Architecture: [`ARCHITECTURE.md`](ARCHITECTURE.md)
- Features: [`features/`](features/)
- One-time device setup for adb: [`setup-devices.md`](setup-devices.md)

## Build & install

Requires JDK 17 and the Android SDK (`local.properties` → `sdk.dir`). No Android Studio
needed.

```fish
./gradlew :core:domain:test :core:protocol:test   # unit tests
./gradlew assembleDebug                           # both APKs
./gradlew :phone:app:installDebug                 # phone over USB
adb connect <tv-ip>:5555
env ANDROID_SERIAL=<tv-ip>:5555 ./gradlew :tv:app:installDebug
```

## Release

```fish
scripts/release.fish 0.5.1   # GitHub release with both APKs; the apps offer it at launch
```

See [`releasing.md`](releasing.md) (when to publish, signing key) and
[`features/app-update.md`](features/app-update.md).
