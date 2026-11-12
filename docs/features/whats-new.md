# What's new, and the update check on the TV home

Mockup: `docs/mockups/whats-new.html` (approved 11/12/2026). Complements `app-update.md`.

## Behavior

**Update check on the TV home.** The TV checks for a new version every time its welcome
screen shows: at launch, when the app comes back to the front, and when a video ends or is
stopped (on a Chromecast the app is rarely really closed, so "at launch" was not enough). At
most once every 10 minutes. Silent: the update dialog appears only when there is one (T0, a
"Checking for updates…" line, was left out). The phone still checks at each launch.

**"What's new" pop-up (P1 / T1).** Once, on the first launch of a new version that has notes,
after an update (a fresh install shows nothing). `CASTIFY 0.6.0`, `What's new`, 2–4 numbered
items in plain words, `OK` and `All versions`. It shows before the update dialog. TV: ◀ ▶ move,
OK presses, Back = OK. The TV wording can differ (remote keys instead of taps).

**Every version (P3 / T2).** Newest first, the installed one marked `INSTALLED`.
- Phone: ⋮ menu → `What's new` (last line), or `All versions` in the pop-up. Back closes.
- TV: `All versions` in the pop-up, or ▼ on the welcome screen (focuses the version label,
  bottom right) then OK. ▲ ▼ scroll by one version, OK / Back close.

## Design

- Domain `app.castify.domain.update`: `ReleaseNote` / `ReleaseNotes.ALL` (the notes, shipped
  in the app, shown offline), `WhatsNew` (pop-up rule + list, port `SeenVersionStore`),
  `UpdateCheckThrottle` (10 min).
- `:shared:update`: `PrefsSeenVersionStore`, `wasUpdated(context)` (`lastUpdateTime` >
  `firstInstallTime`).
- TV: `TvContainer.checkForUpdate()` is throttled; `TvViewModel` calls it when the screen
  turns back to the welcome screen and in `start()` (app back to the front).

## Writing the notes (each release)

Add a `ReleaseNote` at the top of `ReleaseNotes.ALL` before running `scripts/release.fish`:
the version, the date as the app shows it (`Nov 12, 2026`), 2–4 items with a short title and
one sentence for a non-technical user; `tvDetail` when the TV is used differently. A version
without notes shows no pop-up.

## How to test

1. Install a new version over an older one: the pop-up shows once, on phone and TV.
2. Phone: ⋮ → `What's new`. TV: ▼ then OK on the welcome screen.
3. TV: play a video, stop it (Back twice): back on the welcome screen, an available update is
   offered (if the last check was more than 10 min ago).
