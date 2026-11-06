# Releasing

A release = one GitHub release `vX.Y.Z` on the public repo with two files:
`castify-phone.apk` and `castify-tv.apk`. Both apps find it at their next launch
(`docs/features/app-update.md`).

## When to publish

The agent tells the user, after each merge, how many merged changes are not released yet,
and recommends publishing as soon as one of them is something the user can see or feel (a
feature or a bug fix). Docs, tests or tooling alone never call for a release. The user can
also ask for one at any time ("publie"). Publishing only happens on the user's request.

## How

```fish
scripts/release.fish 0.5.1
```

From an up-to-date, clean `main`. The script bumps `castify.version` in
`gradle.properties` through a `Release v0.5.1` PR merged at once (the user's request to
publish covers that merge), runs the unit tests, builds both APKs, then creates the tag
and the release (notes generated from the merged PRs). Use the same version as
`gradle.properties` only for a version never published (the first one, `0.5.0`).

Pick the version: patch (`0.5.1`) for fixes, minor (`0.6.0`) for features.

## Signing key

Release APKs are the debug builds, signed with `~/.android/debug.keystore` on the user's
Mac. Android only accepts an update signed with the same key as the installed app, so:

- always publish from that Mac;
- a copy lives outside the repo, in `~/Backups/castify-debug.keystore`. If the key is lost
  or replaced, restore it (`cp ~/Backups/castify-debug.keystore ~/.android/debug.keystore`);
  otherwise every device needs an uninstall + reinstall (tabs, favorites and history lost).
- Never commit a keystore (`.kiro/steering/privacy.md`).

## Local installs

adb installs of a dev build carry the version of `gradle.properties` (the last release),
so they install over a released app (same `versionCode`) and the app does not offer that
same release again.
