# TV shows: episodes, seasons, Next, Auto next

Mockup: `docs/mockups/episodes.html` (approved 11/12/2026). Scope: `docs/ROADMAP.md`
("Next: TV shows").

## Behavior

**When it shows up.** Only for a video sent from a series episode. Castify recognizes an episode
from its address, page or embedded player, in the convention most streaming sites share:
`…/tv/<id>/<season>/<episode>` (also `series`, `show`), or query parameters
(`?tmdb=<id>&season=<s>&episode=<e>`). The id is a TMDB number or an IMDb `tt…` id. Movies and
other pages: nothing changes.

**Episode list.** Fetched from TVmaze (public, no account; the phone shows "Episode info:
TVmaze"). The TMDB number is mapped to TVmaze through Wikidata (public, no key), or through the
IMDb id. Specials (season 0) are left out. Unknown series or database down: "Episode list
unavailable", the rest of the remote works as before.

**Phone (full remote, P1–P3).**
- Under the controls: `Episodes`, a `Season N ▾` button (season list, the playing one on a
  lighter surface), the season's episodes (picture, `N · Title`, runtime; the playing one
  marked `PLAYING`).
- Tap an episode: `Finding the video…`, then it plays on the TV. Not found:
  `Couldn't find the video of this episode` + `Retry`. The TV keeps playing meanwhile.
- `Auto next` checkbox, the TV's setting (shared by every phone and the TV).

**TV (T1–T5).**
- An `Episodes` button after CC in the controls (series only). Panel: the series title with
  `Auto next` on its right (user), season chips, the season's episodes (4 cards sharing the
  screen width, `PLAYING` badge). It opens focused on the playing episode. ▲ ▼ between rows,
  ◀ ▶ inside a row, OK ticks Auto next / picks the season / plays the episode, Back closes.
  Cards show the episode's picture (TVmaze, Coil), the number while it loads or when there is none.
- `Next episode` bottom right, the last 30 s and at the end, only when the next episode is
  known. OK plays it, Back hides it for this episode. The last episode of a season leads to
  episode 1 of the next season; after the last episode of the series, no button.
- `Auto next` on (off by default): at the end, `Next episode in 10 s`, then it starts; Back
  cancels.
- While the phone finds the video: `Loading S5 E3…` · `Your phone is finding the video`
  (Back cancels). Failure or no answer in 60 s: `Couldn't load S5 E3` + `Retry` / `Back`.

## Design

- Domain `app.castify.domain.series`: `EpisodeAddress` (parse + `withEpisode`), `Show`
  (`next`: next in the season, else first of the next season), `SeriesInfo` (sent with the
  stream), `EpisodeRequest`, `EpisodePlayer` (phone use case). Ports `ShowCatalog`,
  `EpisodeLoader` (phone), `TvSettings` (TV).
- `VideoCatalog.episodeAddress`: the page's address, else the first embedded player address.
- Sending a video from an episode page attaches its `SeriesInfo` (title on the TV:
  `Show · S5 E2 · Title`). Other episodes start in their best quality (user rule, see
  `quality.md`).
- Another episode = `EpisodeLoader.findVideo(address.withEpisode(n))`:
  `WebViewEpisodeLoader` opens the address in a WebView nobody sees, players muted, same
  detection as the browser, keeps the longest video of 3 min or more (2 s after the first
  one); then `/play` with `replace`. The next episode is prepared 2 min 30 s before the end
  (kept 10 min).
- A phone that connects while an episode plays gets the series from `/status`
  (`seriesPageUrl` = the episode's address, `preferredHeight`), with no need to open the site
  again (user request); an episode sent by another phone moves every phone along.
- TV → phone: the TV puts `episodeRequest {id, season, episode}` in `/status`; each phone
  claims it (`/control claimEpisode`, 409 when another phone was first, `EpisodeClaims`);
  the winner answers with `/play`, or `/control episodeFailed` (id). `/control autoNext`
  sets the TV setting (`PrefsTvSettings`), reported in `/status`.

## Limits

- Sites that don't use that address pattern: no episode features (link guessing is a Nice to
  have).
- The phone does the work: it must be on the same Wi-Fi with the app alive (the media
  notification keeps it alive while the TV plays).
- A player that waits for a tap before loading its video is not found by the hidden page.

## How to test

1. On a series site, open an episode, send it to the TV.
2. Phone: open the remote, scroll to `Episodes`, pick another episode and another season.
3. TV: ▼, `Episodes`, pick an episode; let one reach the last 30 s, OK on `Next`.
4. Last episode of a season → `Next` shows S+1 E1.
5. Tick `Auto next` (phone or TV), let an episode end: 10 s countdown, then the next one.
6. Same with the phone locked.
