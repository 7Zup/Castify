# 0001 – Platform: native Android (Kotlin)

_Status: accepted (11/05/2026)_

## Context

The app must detect video streams inside third-party websites on the phone and cast
them to a Chromecast. It may also need to relay/prefetch the stream (headers,
cookies, buffering), which means running while the screen is off.

## Options

- **Web app**: cannot embed third-party sites (frame blocking) nor observe their
  network traffic (sandbox/CORS). Rejected.
- **iOS native**: WKWebView exposes no network interception for media requests;
  background execution is too restricted for a long-running proxy. Rejected (the
  user is on Android anyway).
- **Cross-platform (Flutter / React Native)**: Cast and WebView plugins are less
  mature; no benefit for a single-platform app. Rejected.
- **Native Android (Kotlin)**: `WebViewClient.shouldInterceptRequest` sees every
  request; foreground services allow a long-running local proxy; official Google
  Cast sender SDK. **Chosen.**

## Consequences

- Kotlin + Jetpack Compose, Gradle multi-module (`:domain`, `:data`, `:app`).
- A custom Cast Web Receiver (HTML/JS, Shaka Player) lives in `receiver/`, hosted
  over HTTPS (GitHub Pages). Needs a Google Cast Developer account (one-time 5 USD)
  and the Chromecast serial registered for testing. v0 uses the default receiver.
- Builds and installs are done from the Mac with the Android SDK and `adb`.
