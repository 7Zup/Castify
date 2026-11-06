# In-app update + version label

Both apps check the latest GitHub release at each launch and offer to update in one tap.
Mockup: `docs/mockups/app-update.html` (P0–P4 phone, T0–T3 TV). Publishing: `docs/releasing.md`.

## Behavior

- **Version label**: `v0.5.0` in the disabled gray, bottom right of the phone home screen
  and of the TV welcome screen.
- **Silent check** at each launch (activity created, not restored): `GET
  api.github.com/repos/7Zup/Castify/releases/latest`, no token. Up to date, no release,
  offline or GitHub down → nothing shown.
- **Dialog** when the release is strictly newer and carries this app's APK
  (`castify-phone.apk` / `castify-tv.apk`): `Update available` · `Update` / `Not now`.
  `Not now` closes it; the next launch asks again.
- **Download** with a progress bar (MB) and `Cancel` (a tap outside does not cancel).
- **Install**: Android's own confirmation. The first time, Android first asks to allow
  "Install unknown apps" for Castify, then comes back to the install. On Android 12+ (the
  TV) the confirmation is skipped from the 2nd update on (Castify is then its own installer
  of record); the phone (Android 10) always asks. The app restarts on the new version.
- **Failure** (download dropped, install refused): `Update failed` · `Retry` / `Not now`.
  Cancelling Android's confirmation shows nothing until the next launch.
- **TV**: the dialog only shows on the welcome screen, never over a video. D-pad: ◀ ▶ move
  between the buttons (focus starts on `Update` / `Retry`), OK presses, Back = `Not now`
  (or `Cancel` while downloading). A video sent from a phone drops the prompt (or the
  running download): the next launch asks again.

## Design

- Domain (`core/domain/.../update/`): `AppVersion` (parse `v0.5.0`, numeric compare),
  `AppKind` (which APK), `AppUpdate.from(release, current, kind)`, the `AppUpdater` state
  machine (`Idle` → `Available` → `Downloading` → `Installing`, or `Failed`), ports
  `UpdateSource` and `UpdateInstaller`.
- Adapters (`:shared:update`, used by both apps): `GitHubReleaseSource` (HTTP + `org.json`),
  `ApkInstaller` (download into `cacheDir/update/`, then `PackageInstaller` session) and
  `InstallResultReceiver` (starts Android's confirmation, reports the outcome).
- Version: one `castify.version` in `gradle.properties` for both apps; `versionCode` is
  derived (`0.5.0` → 500).

## Limits

- APKs are debug builds signed with the Mac's debug key (phone ~22 MB, TV ~85 MB). A
  different key means Android refuses the update: see `docs/releasing.md`.
- If the user leaves the app while it downloads, Android may block the confirmation from
  showing; the next launch offers the update again.
- Updating the TV app stops it: that is why the prompt never shows during a video.

## How to test

1. Install a build with the updater by adb (`v0.5.0`), publish `v0.5.0`: launch → no
   dialog, `v0.5.0` bottom right.
2. Publish `v0.5.1`, relaunch: dialog. `Not now`, relaunch: dialog again. `Update` →
   progress → (first time) allow unknown apps → Android's confirmation → app restarts,
   `v0.5.1` bottom right.
3. Same on the TV with the remote, from the welcome screen.
4. Turn the Wi-Fi off during the download: `Update failed`; `Retry` once back online.
