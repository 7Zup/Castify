# Tech stack & commands

See ADRs `docs/decisions/0001-platform.md` and `0002-tv-receiver.md`.

## Stack

- Kotlin, Jetpack Compose (Material 3 on phone, Compose for TV on the TV), coroutines + Flow.
- Gradle (Kotlin DSL) with the wrapper (`./gradlew`) and a version catalog
  `gradle/libs.versions.toml`. **All versions are pinned there**, nowhere else.
- Phone: Android `WebView` for the in-app browser.
- TV: AndroidX Media3 ExoPlayer (HLS, DASH, progressive).
- Phone ↔ TV: Android NSD (mDNS) for discovery, small HTTP/JSON API served by the TV app,
  kotlinx.serialization for messages.
- No Google Cast SDK.
- DI: manual constructor injection wired in each app module (no framework until it hurts).
- Tests: JUnit + kotlinx-coroutines-test in `:core:*` (no device needed).

## Environment (user's Mac)

- Android SDK: `~/Library/Android/sdk` (platforms 34 and 36, build-tools up to 36.1).
- JDK 17 (`/opt/homebrew/bin/java`), `adb` on the PATH. No global Gradle: always use
  `./gradlew`. Shell is fish.
- Targets: `compileSdk`/`targetSdk` 36. `minSdk` 26 (phone), 31 (TV).
- Toolchain: Gradle 8.13, AGP 8.13.2, Kotlin 2.4.21. **AGP 8.13 caps compileSdk at 36**:
  AndroidX releases that require compileSdk 37 / AGP 9 (Compose UI 1.12+, Compose BOM
  2026.08+, activity 1.13+, lifecycle 2.11+) must not be picked until the toolchain is
  upgraded deliberately (Gradle 9 + AGP 9 + platform 37, its own branch). Same for Coil:
  3.6+ pulls Compose 1.12, so Coil is pinned to 3.5.0. Before adding any library, check
  which Compose/AndroidX versions it pulls.
- Ktor server (TV) needs the `packaging.resources.excludes` in `tv/app/build.gradle.kts`.
- Application ids: `app.castify.phone`, `app.castify.tv`.
- Devices (models, serials, IPs, TV names): in the gitignored `.kiro/steering/local.md`
  (see `privacy.md`).
- TV: Chromecast with Google TV (Android TV 14), **Wireless debugging** (not port 5555),
  already paired with the Mac. Connect port changes: find it with mDNS
  (`docs/setup-devices.md`), then `adb connect <tv-ip>:<port>`. Smoke test without the phone:
  `curl -d '{"url":"https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8","type":"HLS","pageUrl":"x"}' http://<tv-ip>:47800/play`.

## Commands

```fish
./gradlew :core:domain:test :core:protocol:test   # pure unit tests
./gradlew assembleDebug                           # build both APKs
adb devices                                       # list phone (USB) and TV (ip:5555)
adb connect <tv-ip>:5555                          # attach the TV over Wi-Fi
./gradlew :phone:app:installDebug                 # install on the phone
ANDROID_SERIAL=<tv-ip>:5555 ./gradlew :tv:app:installDebug   # install on the TV
adb logcat -s Castify                             # app logs (tag "Castify")
```

With several devices attached, target one with `ANDROID_SERIAL` (fish:
`env ANDROID_SERIAL=... ./gradlew ...`). The agent may run builds, tests and installs.
Using the apps and checking the TV is done by the user.

**While the user is testing with the agent in the chat, installing is fine even if something plays**
(user, 11/12/2026: "si on parle ici, tu peux toujours couper l'épisode, c'est en mode debug").
Otherwise: **never reinstall or force-stop the TV app without asking first**: an install kills the app
(`am_kill … installPackageLI`) and drops the user out of the movie they are watching. This
happened ~16 times on 11/05/2026 and looked like crashes to the user. Check first:
`curl -s http://<tv>:47800/status` → if `state` is not `IDLE`, ask before installing.
