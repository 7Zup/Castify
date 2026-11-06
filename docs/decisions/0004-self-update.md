# 0004 – Self-update from GitHub Releases, debug-signed APKs

_Status: accepted (11/06/2026)_

## Context

Both apps are sideloaded (no Play Store). Every update needed the Mac, adb, and the TV's
Wireless debugging port. The user wants to publish on GitHub and update from the app.

## Options

1. **GitHub Releases on the public repo + `PackageInstaller`** in the app: free, no server,
   no token (the repo is public), one release ships both APKs.
2. Play Store (internal testing track): review, a developer account, and the TV app's
   LAN server / sideload use case fit badly.
3. A third-party updater service (Firebase App Distribution, etc.): accounts and a tester
   app on each device, no TV support.

Signing: (a) keep the current debug key; (b) a new release key. A new key changes the
signature: Android then refuses the update, every device needs an uninstall (tabs,
favorites, history lost).

## Decision

Option 1 with signing (a). Shared adapters in `:shared:update`, rules in
`core/domain/update/`. One version for both apps in `gradle.properties`; `versionCode`
derived from it. Published with `scripts/release.fish` (`docs/releasing.md`).

## Consequences

- The debug key on the user's Mac is now critical: backed up outside the repo; releases
  are only built on that Mac.
- APKs are debug builds (larger, debuggable). Minified release builds are a Nice to have
  (R8 rules for Ktor / serialization to verify first).
- Both apps get every release, even when only one changed.
- The first build with the updater is installed by adb one last time.
