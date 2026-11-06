# Castify

Two Android apps: the **phone** app is a browser that detects the video stream playing
in a website; the **TV** app (Chromecast with Google TV) plays it with ExoPlayer, using
the headers/cookies captured on the phone. No screen mirroring, no Google Cast.

- Status: [`docs/STATUS.md`](docs/STATUS.md)
- Roadmap: [`docs/ROADMAP.md`](docs/ROADMAP.md)
- Architecture: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- Features: [`docs/features/`](docs/features/)
- One-time device setup: [`docs/setup-devices.md`](docs/setup-devices.md)

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

## License

Source available under the [PolyForm Noncommercial License 1.0.0](LICENSE.md): free to
read, use and modify for any noncommercial purpose; selling it or using it commercially
is not allowed.
