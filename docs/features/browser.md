# Browser (phone): tabs, favorites, history

Mockup validated by the user: `docs/mockups/mobile-browser.html` (11/05/2026).

## Behavior

**Top bar** (no ← button: Android back goes back in the page)
- Address field: a URL (`https://` added when missing) or a search (DuckDuckGo). While typing,
  a → (Go) button shows; the keyboard Enter key submits too. Otherwise, on a page, a **Reload** button sits at
  the end of the field (user choice, 11/05/2026: Reload out of the menu, ★ only in the menu).
- Cast button (`remote-control.md`), tabs counter, ⋮ menu. All three icons have the same
  optical size (28 dp, slightly bigger after user feedback), evenly spaced.

**⋮ menu**
- Icon row: Forward (dimmed when there is nothing to go forward to), ★ (add / remove the
  page from favorites, red when it is one). Dimmed on the home screen.
- `New tab`, `Favorites`, `History`.

**Tabs** (Chrome style)
- Grid with title, ✕, a preview (screenshot taken when leaving the page), and `N videos` when
  videos were found. The current tab has a red border. New tab: `+` at the top right and
  `+ New tab` at the bottom (user feedback); ⋮ → `Close all tabs` (confirmation).
- ✕ on the only tab (user rule): it is replaced by an empty tab and the home screen shows
  right away.
- **Only the active tab is loaded** (user rule). Other tabs keep their address, title and
  preview and load nothing until opened; opening one reloads its page from the start (the TV
  keeps playing what it received).
- Tabs of the last session are restored on launch; only the active one loads.
- At most **100 tabs** (user choice): `You can have up to 100 tabs. Close one first.`
- Closing the active tab opens its right neighbor; `Close all tabs` leaves one empty tab.

**Home screen** (a new tab, mockup `docs/mockups/home-screen-final.html`, validated
11/05/2026): Castify logo and name centered, a big `Search or type a website` field under it
(the top bar keeps only Cast, tabs counter and ⋮ on this screen), `FAVORITES` tiles (up to 7
+ `Add`, which explains how to add one) with `See all`, then `RECENT` (5 most recently
visited pages) with `History`; empty: `Pages you visit will show up here.`

**Favorites**: name only (user: no address line). Tap = open in the current tab; ⋮ = `Open
in new tab`, `Rename`, `Remove`. Empty: `No favorites yet. Tap ★ in the menu to add the page you are on.`

**History**: **every page visited** in any tab (links, address bar, back/forward; user rule,
11/05/2026). A search results page is shown as its query. Newest first, grouped `TODAY`, `YESTERDAY`, `NOV 2, 2026`; time
`HH:mm` (`· search` for searches); ✕ per entry; `Clear` (confirmation); `Search history`
field. Visiting the same page again (address without `#fragment`) or the same search moves it
to the top. Tapping an entry opens its exact address in the current tab. At most **1000 entries** (oldest
dropped). Empty: `No history yet…`; no match: `No match for “…”`.

Popups and new windows stay blocked (ads use them to hijack the page). A **link the user
taps** that asks for a new tab (`target=_blank`) opens **in the current tab** (user rule,
11/05/2026), with the link's own address (an image inside a link works too). A script that
opens a window on any tap (ad popups) is still blocked, so a site that navigates only
through `window.open()` on a non-link element won't move: report it if a site needs it.
Non-web links (`intent:`, `market:`, `tel:`, app links) are ignored instead of showing an
error page; an `intent:` link with a web fallback opens that fallback.

**Errors** (mockup `docs/mockups/offline-errors.html`, P1–P3; rule `PageError.of`): when the
page itself can't reach its server (DNS, connect, timeout, cut), a Castify screen replaces
Android's raw error page:
- phone offline: `No internet connection` · `Check that your phone is on Wi-Fi, then try again.` · **Retry**;
- phone online: `Can't reach <site>` · `The website isn't answering. It may be down, or the
  address may be wrong.` · **Retry** · **Go to home**.
Retry reloads the page; nothing reloads by itself (user rule). Other failures (bad
certificate…) keep the browser's own page. On the home screen while offline, a banner
`No internet connection` · `Retry`; favorites and history stay usable.

**Background**: when the app leaves the screen, the page goes silent: every `<video>` /
`<audio>` is paused, the WebView is paused and its timers stop (user rule: only the TV plays;
the media notification is the TV's remote, never the phone's player). Back in the app, the
page resumes but the video stays paused.

**Address bar while editing**: the field takes the whole bar (the icons hide, like Chrome) and
the cursor goes to the end of the address, so the end of a long address shows and can be edited.
The field keeps its own text and cursor until it is left (a site changing its address
meanwhile no longer resets them).
Opening the remote, a sheet or a screen, or touching the page, leaves the field and hides the
keyboard.

**Site players**: a page's own fullscreen button works (`onShowCustomView`: the player covers
the app, system bars hidden, Back or the player's button leaves). The WebView is laid out
`MATCH_PARENT`: with Compose's default (wrap content) CSS `vh` was 0 and panels sized in vh
(a player's Settings / Subtitles menus) opened collapsed, below the screen. Picture-in-picture
is not available in a WebView (the site's PiP button does nothing).

**Tab history**: each tab keeps its back / forward pages (`WebView.saveState`, files in
`tab-history/`), across tab switches and app restarts, so Android Back and a site's own ←
work like in Chrome. Going to the home screen starts the tab over. A page opened directly
(typed address, favorite) has nothing before it, as in Chrome. Same pause as soon as a video is sent to the TV
(user choice), and again once the TV took it: the phone never plays or downloads it at the
same time as the TV. The pause reaches players inside iframes (cross-origin included):
`PageMedia` injects a document-start script in every frame (`androidx.webkit`
`addDocumentStartJavaScript` + `addWebMessageListener`); on an old WebView without these
features only the main frame is paused.

## Design

- Domain `app.castify.domain.browser`: `BrowserTabs` (+ `TabStore`), `BrowsingHistory`
  (+ `HistoryStore`), `Favorites` (+ `FavoriteStore`). `OpenPage` normalizes the typed input;
  the view model calls `BrowsingHistory.visit(url)` on every page change.
- `:phone:data` `BrowserStores.kt`: JSON in SharedPreferences (`castify_browser`), tab
  previews as JPEG in the cache (`TabPreviewStore`, cleaned at launch). `PrefsLastUrlStore`
  is legacy: read once to migrate the old "last page" into the first tab.
- `BrowserScreen`: one `WebView` per active tab (`key(tab.id)`), destroyed when the tab
  changes, so each tab has its own back stack. Overlays: `TabsScreen`, `FavoritesScreen`,
  `HistoryScreen` (`ui/Library.kt`); Back closes them.
- `VideoCatalog` is reset on every tab change; the tab's video count is remembered for the grid.

## How to test

- Unit: `BrowserTest` (tabs, history, favorites), `PlaybackTest` (address normalization).
- Manual: see `docs/STATUS.md`.
