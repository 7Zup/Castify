# Design: a Netflix-like theme

The user wants Castify to look and feel **like Netflix**: dark, red accent, content first.
This applies to both apps (phone and TV) and to the launcher icon / TV banner.

## Palette

| Role | Color |
| --- | --- |
| Background | `#141414` (near black); pure `#000000` behind video |
| Surface (cards, sheets, panels) | `#1F1F1F`, raised `#2A2A2A` |
| Accent / primary actions | Netflix red `#E50914` (pressed `#B20710`) |
| Primary text | `#FFFFFF` |
| Secondary text, icons | `#B3B3B3`; disabled `#6D6D6D` |
| Errors | red accent + white text (no extra color) |
| Status "ready / OK" dot | green `#46D369` (Netflix's green): status dots and "done" checkmarks (TV welcome steps, user choice 11/06/2026) only, never text or backgrounds |
| Status "standby" dot | white `#FFFFFF`, slowly pulsing (TV welcome: app open, no phone connected) |

- Dark theme only: no light mode, the app ignores the system light setting.
- Red is rare and meaningful: the main action, the active item, the brand. Never large red
  backgrounds behind text blocks.

## Look & feel

- **Content first**: thumbnails are the hero (16:9, rounded 4 dp, no borders); text is
  minimal and short.
- Bold, heavy titles; regular body text. Sans-serif (system default).
- Flat surfaces, no gradients except the dark scrim over images/video.
- TV: large type readable from the couch, clear focus state (white outline + slight scale),
  everything reachable with D-pad + OK + Back.

## Rules for the agent

- Use these tokens through one theme file per app (no hard-coded colors in screens).
- Any new screen or component follows this file; if it needs something not covered here,
  ask the user, then extend this file.
- Logo (chosen by the user, 11/05/2026): a red **ribbon "C"** (dark-to-red shade, a fold
  over the top, Netflix style) with **white cast waves** inside, on black. Sources:
  `phone/app/src/main/res/drawable/ic_launcher_foreground.xml` (adaptive icon) and
  `tv/app/src/main/res/drawable/banner.xml` (same paths, scaled). Keep them in sync.
