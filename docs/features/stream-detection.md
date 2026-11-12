# Stream detection and video list (phone)

## Behavior

- Every request of the page is reported to the domain. A video icon in the top bar, left of
  Cast, shows the count in a red badge (no badge = no video). It never covers the page: the
  floating `N videos found` button it replaced hid the sites' own player buttons (user's pick,
  option A of `docs/mockups/videos-button.html`). The list resets on each new page.
- **Ads are hidden**: streams from known ad hosts never appear.
- Tapping the button opens a sheet with one card per video, and nothing else:

  | Thumbnail | Duration | Quality |
  | --- | --- | --- |
  | site image, 16:9 | `1:42:10`, `Live`, `…` while reading | best quality, e.g. `1080p`; `—` unknown; `…` while reading |

  No thumbnail of its own → gray box with a movie icon.
- **Videos with no known duration that are not live are hidden** (user rule): they are
  decoys, dead links or UI clips (e.g. a site's `screenshare.webm` tutorial). While a
  manifest is being read, the card shows `…` and may then disappear.
- Quality names are the usual ones, taken from the **width**: a widescreen 1920x800 movie
  is `1080p`, 1280x532 is `720p`, 854x356 is `480p`, 640x266 is `360p` (`QualityLabel`).
  The real height is what the TV locks to.
- Tap a card:
  - several qualities → `Choose a quality` list (`1080p`, `720p`…, best first; no `Auto`:
    switching qualities can make some streams jump by a few seconds);
  - one or unknown quality → straight to the TV picker (`tv-playback.md`).
- Nothing detected: `No video found. Start the video in the page, then open this panel again.`

## Design

- `SniffingWebViewClient` (`:phone:data`) reports, through `BrowserEvents`:
  - `shouldInterceptRequest`: every network request, with `User-Agent`, `Referer` and the
    `Cookie` for the URL added;
  - an injected script, every 2 s: each `<video>` (source, `poster`, `duration`,
    `videoHeight`) and the page share image (`og:image` / `twitter:image`).
- `StreamClassifier` (`:core:domain`) keeps entry points: `.m3u8` / HLS MIME → HLS,
  `.mpd` → DASH, `.mp4 .webm .mkv .mov .m4v` or `video/*` → progressive. Segments,
  `blob:` and non-HTTP URLs are dropped; known ad hosts are flagged `likelyAd`.
- `VideoCatalog` (`:core:domain`) builds the list:
  - drops ads and duplicates; manifests before files, then detection order;
  - inspects each new HLS/DASH stream once with `InspectStream` → `ManifestFetcher`
    (`HttpManifestFetcher`, page headers, 2 MB cap). HLS master: qualities from
    `RESOLUTION`, duration from the first variant; HLS media: sum of `#EXTINF`, live if no
    `#EXT-X-ENDLIST`; DASH: `mediaPresentationDuration`, `type="dynamic"`, `Representation@height`;
  - hides sub-playlists of a known master (variants, audio, I-frames): one video per master;
  - progressive: duration and width/height from the matching `<video>`;
  - hides any video whose details are known and have no duration while not live;
  - thumbnail: poster of the matching `<video>`. HLS/DASH may fall back to the poster of a
    streaming player (`<video>` with a `blob:` source), then the page image. A progressive
    file never borrows another video's image;
  - results arriving after a page change are ignored (page generation).
- Thumbnails load with Coil, sending the page `Referer` and `User-Agent`.
- Video title = page `og:title` or `document.title`, cleaned by `PageTitle` (site name after
  ` - ` / ` | `, `Watch …`, `… Online Free`, `… en streaming VF` removed); null if nothing
  meaningful is left. Shown on the TV time bar.
- The picked height is sent to the TV as `preferredHeight` (see `tv-playback.md`).

## Limits

- Request MIME types are unknown at interception time: detection is mostly by URL extension.
- The page's traffic is never altered (user rule, 11/06/2026: "the phone refuses nothing").
  Its own player only stops playing for nothing: paused in the background and when the video
  is sent to the TV (`browser.md`).- Progressive file without a visible `<video>` (player in a cross-origin iframe, e.g.
  videosite.example): MP4/MOV duration and size are read from the file header (`Mp4Probe`, HTTP
  Range, usually one 64 KB request; jumps over `mdat` when `moov` is at the end). Up to 16 MB for a
  big `moov` (fine for the user). Servers
  that ignore Range → nothing read → hidden. WebM/MKV: no header probe yet → hidden.
- YouTube (`googlevideo.com/videoplayback`, separate audio/video by byte ranges) is not
  detected; YouTube has its own Cast button.
- Language is not shown (rarely available in streams).
- DRM streams are detected but will not play on the TV.

## How to test

- Unit: `./gradlew :core:domain:test` (`StreamDetectionTest`, `ManifestParsingTest`, `VideoCatalogTest`).
- Manual: open 2–3 sites, start a video, open the list: thumbnail, duration, quality, no URL,
  no ad. Pick a quality, check the TV diagnostics `Format` line.
