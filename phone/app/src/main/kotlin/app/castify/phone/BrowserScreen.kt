package app.castify.phone

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import app.castify.phone.ui.PageErrorScreen
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.castify.domain.model.DetectedVideo
import app.castify.domain.model.TvDevice
import app.castify.domain.model.NearbyTv
import app.castify.domain.model.DeviceKind
import app.castify.domain.model.TvStatus
import app.castify.domain.session.TvSession
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import app.castify.phone.data.PageMedia
import app.castify.phone.ui.CastifyColors
import app.castify.phone.ui.NowPlayingBar
import app.castify.phone.ui.ExpandingPlayer
import app.castify.phone.ui.rememberRemoteExpansion
import app.castify.phone.ui.SubtitlesSheet
import app.castify.domain.playback.PlayerCommand
import app.castify.phone.ui.Thumbnail
import app.castify.phone.ui.formatDuration
import app.castify.phone.ui.BrowserMenu
import app.castify.phone.ui.FavoritesScreen
import app.castify.phone.ui.HistoryScreen
import app.castify.phone.ui.HomeScreen
import app.castify.phone.ui.TabsCountIcon
import app.castify.phone.ui.TabsScreen
import android.graphics.Bitmap
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Canvas
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key

/** Full-screen pages over the browser. */
private enum class Overlay { NONE, TABS, FAVORITES, HISTORY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    installSniffer: (WebView) -> Unit,
    savePreview: (String, Bitmap) -> Unit,
    tabHistory: TabHistory,
    loadPreview: (String) -> Bitmap?,
    version: String,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val tv by viewModel.tv.collectAsStateWithLifecycle()
    val tabsState by viewModel.tabsState.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteList.collectAsStateWithLifecycle()
    val history by viewModel.historyEntries.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var videoSheetOpen by remember { mutableStateOf(false) }
    var connectSheetOpen by remember { mutableStateOf(false) }
    var disconnectDialog by remember { mutableStateOf(false) }
    val remote = rememberRemoteExpansion()
    var fullscreen by remember { mutableStateOf<Fullscreen?>(null) }
    var subtitlesOpen by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val requestNotifications = rememberNotificationPermission()
    val playing = tv.connected && tv.nowPlaying?.isActive == true
    val activeTab = tabsState.active
    val onHome = activeTab.url == null
    val isFavorite = remember(activeTab.url, favorites) { viewModel.isFavorite(activeTab.url) }

    /** Save the current page's preview before leaving it (tab grid, tab switch). */
    fun rememberCurrentPreview() {
        webView?.let { view -> capturePreview(view)?.let { savePreview(activeTab.id, it) } }
    }

    LaunchedEffect(Unit) { viewModel.loadRequests.collect { url -> webView?.loadUrl(url) } }
    LaunchedEffect(Unit) { viewModel.reloadRequests.collect { webView?.reload() } }
    // P4: the connected TV stopped answering → message with Retry until it answers again.
    val tvName = tv.device?.name
    LaunchedEffect(tv.connected, tv.reachable, tvName) {
        if (tv.connected && !tv.reachable && tvName != null) {
            val result = snackbar.showSnackbar("Can't reach $tvName", actionLabel = "Retry", duration = SnackbarDuration.Indefinite)
            if (result == SnackbarResult.ActionPerformed) viewModel.retry()
        }
    }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { viewModel.pausePageMedia.collect { webView?.let(PageMedia::pause) } }
    LaunchedEffect(playing) { if (!playing) remote.snapClosed() }
    BackHandler(enabled = state.canGoBack && !remote.visible && overlay == Overlay.NONE && !onHome) { webView?.goBack() }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = CastifyColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AddressBar(
                text = state.addressBar,
                onHome = onHome,
                cast = tv,
                videoCount = if (onHome) null else videos.size,
                onVideos = { videoSheetOpen = true },
                tabCount = tabsState.tabs.size,
                onTextChange = viewModel::onAddressChange,
                onSubmit = viewModel::onAddressSubmit,
                onCast = { if (tv.connected) disconnectDialog = true else connectSheetOpen = true },
                onTabs = { rememberCurrentPreview(); overlay = Overlay.TABS },
                onReload = { webView?.reload() },
            ) {
                BrowserMenu(
                    onHome = onHome,
                    canGoForward = state.canGoForward,
                    isFavorite = isFavorite,
                    onForward = { webView?.goForward() },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onNewTab = { rememberCurrentPreview(); viewModel.newTab() },
                    onFavorites = { overlay = Overlay.FAVORITES },
                    onHistory = { overlay = Overlay.HISTORY },
                )
            }
        },
        bottomBar = {
            if (playing) {
                NowPlayingBar(
                    tv,
                    expansion = remote,
                    onCommand = viewModel::control,
                    onSubtitles = { subtitlesOpen = true },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val url = activeTab.url
            // Home screen: the tab starts over, its old pages are not "back" any more. Runs after
            // the WebView's own save on leaving.
            LaunchedEffect(activeTab.id, url == null) { if (url == null) tabHistory.forget(activeTab.id) }
            if (url == null) {
                HomeScreen(
                    address = state.addressBar,
                    onAddressChange = viewModel::onAddressChange,
                    onSubmit = viewModel::onAddressSubmit,
                    favorites = favorites,
                    recent = history,
                    onOpenUrl = viewModel::openUrl,
                    onAllFavorites = { overlay = Overlay.FAVORITES },
                    onHistory = { overlay = Overlay.HISTORY },
                    onAddFavoriteHint = {
                        viewModel.showMessage("Open a page, then tap ★ in the menu to add it")
                    },
                    offline = !online,
                    onRetryNetwork = viewModel::retryNetwork,
                    version = version,
                )
            } else {
                key(activeTab.id) {
                    TabWebView(activeTab.id, url, installSniffer, tabHistory, onFullscreen = { fullscreen = it }) { webView = it }
                }
                // Over the WebView (kept underneath so Retry can reload it).
                state.pageError?.let { error ->
                    PageErrorScreen(error, onRetry = viewModel::retryPage, onHome = viewModel::goHome)
                }
            }
        }
    }

    when (overlay) {
        Overlay.NONE -> Unit
        Overlay.TABS -> TabsScreen(
            tabs = tabsState.tabs,
            activeId = tabsState.activeId,
            loadPreview = loadPreview,
            onSelect = { viewModel.selectTab(it); overlay = Overlay.NONE },
            // Closing the only tab leaves an empty tab: show it (the home screen) right away.
            onClose = { id ->
                val last = tabsState.tabs.size == 1
                viewModel.closeTab(id)
                if (last) overlay = Overlay.NONE
            },
            onCloseAll = { viewModel.closeAllTabs(); overlay = Overlay.NONE },
            onNewTab = { viewModel.newTab(); overlay = Overlay.NONE },
            onBack = { overlay = Overlay.NONE },
        )
        Overlay.FAVORITES -> FavoritesScreen(
            favorites = favorites,
            onOpen = { viewModel.openUrl(it); overlay = Overlay.NONE },
            onOpenInNewTab = { rememberCurrentPreview(); viewModel.newTab(it); overlay = Overlay.NONE },
            onRename = viewModel::renameFavorite,
            onRemove = viewModel::removeFavorite,
            onBack = { overlay = Overlay.NONE },
        )
        Overlay.HISTORY -> HistoryScreen(
            entries = history,
            search = viewModel::searchHistory,
            onOpen = { viewModel.openUrl(it); overlay = Overlay.NONE },
            onRemove = viewModel::removeHistory,
            onClear = viewModel::clearHistory,
            onBack = { overlay = Overlay.NONE },
        )
    }

    if (videoSheetOpen) {
        ModalBottomSheet(onDismissRequest = { videoSheetOpen = false }, containerColor = CastifyColors.Surface) {
            CastSheet(
                viewModel = viewModel,
                videos = videos,
                connectedTo = tv.device,
                sendingTo = state.sendingTo,
                onConnect = requestNotifications,
            ) { videoSheetOpen = false }
        }
    }

    if (connectSheetOpen) {
        ModalBottomSheet(onDismissRequest = { connectSheetOpen = false }, containerColor = CastifyColors.Surface) {
            val devices by viewModel.devices.collectAsStateWithLifecycle()
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                DevicePicker(devices, onRefresh = viewModel::refreshDevices) { device ->
                    requestNotifications()
                    viewModel.connect(device)
                    connectSheetOpen = false
                }
            }
        }
    }

    if (disconnectDialog) {
        AlertDialog(
            onDismissRequest = { disconnectDialog = false },
            title = { Text("Connected to ${tv.device?.name.orEmpty()}") },
            confirmButton = {
                TextButton(onClick = { viewModel.disconnect(); disconnectDialog = false }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { disconnectDialog = false }) { Text("Cancel") } },
            containerColor = CastifyColors.SurfaceRaised,
        )
    }

    state.pendingReplace?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelReplace,
            title = { Text("Replace the video playing on ${pending.tvName}?") },
            confirmButton = { TextButton(onClick = viewModel::confirmReplace) { Text("Replace") } },
            dismissButton = { TextButton(onClick = viewModel::cancelReplace) { Text("Cancel") } },
            containerColor = CastifyColors.SurfaceRaised,
        )
    }

    if (remote.visible) {
        ExpandingPlayer(
            tv,
            expansion = remote,
            onCommand = viewModel::control,
            onRetry = viewModel::retry,
            onSubtitles = { subtitlesOpen = true },
        )
    }

    fullscreen?.let { FullscreenPlayer(it) }

    if (subtitlesOpen && playing) {
        tv.nowPlaying?.let { now ->
            SubtitlesSheet(
                now,
                onPick = { id -> viewModel.control(PlayerCommand.SelectSubtitle(id)) },
                onDismiss = { subtitlesOpen = false },
            )
        }
    }
}

/** Android 13+: asks once for the notification permission (media controls in the background). */
@Composable
private fun rememberNotificationPermission(): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(launcher) {
        { if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}

/**
 * One WebView per **active** tab only (user rule: other tabs stay inactive). Recreated when
 * the active tab changes, so each tab has its own back stack; destroyed when it leaves.
 */
/** A site's player in fullscreen (`WebChromeClient.onShowCustomView`). */
class Fullscreen(val view: View, val callback: WebChromeClient.CustomViewCallback)

/**
 * The site's player over the whole app, system bars hidden (swipe from an edge shows them for a
 * moment). Back, or the player's own exit button, leaves fullscreen.
 */
@Composable
private fun FullscreenPlayer(fullscreen: Fullscreen) {
    BackHandler { fullscreen.callback.onCustomViewHidden() }
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(fullscreen, window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                (fullscreen.view.parent as? ViewGroup)?.removeView(fullscreen.view)
                addView(fullscreen.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        },
        onRelease = { it.removeAllViews() },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Each tab's back / forward history, kept across WebView re-creations and app restarts. */
interface TabHistory {
    fun save(tabId: String, webView: WebView)
    /** False = nothing usable: the caller loads the address. */
    fun restore(tabId: String, webView: WebView, url: String): Boolean
    fun forget(tabId: String)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TabWebView(
    tabId: String,
    initialUrl: String,
    installSniffer: (WebView) -> Unit,
    tabHistory: TabHistory,
    onFullscreen: (Fullscreen?) -> Unit,
    onReady: (WebView?) -> Unit,
) {
    val context = LocalContext.current
    val webView = remember(tabId) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            // New windows are never created: ads use them to hijack the page. But a link the
            // user tapped that asks for a new tab (target=_blank) opens in this tab (user rule).
            // Scripts opening windows on any tap (ad popups) stay blocked: only a real link
            // under the finger is followed, with its own address.
            settings.setSupportMultipleWindows(true)
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    if (!isUserGesture || isDialog) return false
                    fun openHere(url: String?) {
                        if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) view.loadUrl(url)
                    }
                    val hit = view.hitTestResult
                    when (hit.type) {
                        WebView.HitTestResult.SRC_ANCHOR_TYPE -> openHere(hit.extra)
                        // Image inside a link: hit.extra is the image, ask the WebView for the link.
                        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                            val handler = Handler(Looper.getMainLooper()) { msg -> openHere(msg.data.getString("url")); true }
                            view.requestFocusNodeHref(handler.obtainMessage())
                        }
                    }
                    return false
                }

                // A site's own fullscreen button (its player): the player view is shown over the
                // whole app. Without this the WebView reports fullscreen as unavailable.
                override fun onShowCustomView(view: View, callback: CustomViewCallback) = onFullscreen(Fullscreen(view, callback))
                override fun onHideCustomView() = onFullscreen(null)
            }
            // Match the parent explicitly: Compose's default (wrap content) makes the page's CSS
            // `vh` 0, so panels sized in vh (a site player's Settings / Subtitles menus) collapse.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            installSniffer(this)
            // Back / forward history kept across restarts: Android Back and the sites' own back work.
            if (!tabHistory.restore(tabId, this, initialUrl)) loadUrl(initialUrl)
        }
    }
    DisposableEffect(webView) {
        onReady(webView)
        onDispose {
            onReady(null)
            tabHistory.save(tabId, webView)
            webView.stopLoading()
            webView.destroy()
        }
    }
    // App in the background: the page must go silent (user rule). Only the TV plays; the
    // media notification is the TV's remote, never the phone's player.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(webView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    // onPause() is best effort for media: pause every video/audio element too.
                    PageMedia.pause(webView)
                    tabHistory.save(tabId, webView)
                    webView.onPause()
                    webView.pauseTimers()
                }
                Lifecycle.Event.ON_START -> {
                    webView.resumeTimers()
                    webView.onResume()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
}

/** Small screenshot of the page for the tab grid (a third of the screen size). */
private fun capturePreview(webView: WebView): Bitmap? = runCatching {
    if (webView.width == 0 || webView.height == 0) return null
    val scale = 1f / 3
    val bitmap = Bitmap.createBitmap((webView.width * scale).toInt(), (webView.height * scale).toInt(), Bitmap.Config.RGB_565)
    val canvas = Canvas(bitmap)
    canvas.scale(scale, scale)
    canvas.translate(-webView.scrollX.toFloat(), -webView.scrollY.toFloat())
    webView.draw(canvas)
    bitmap
}.getOrNull()

/**
 * Top bar (no ← button: Android back does it): address field with a Reload button (on a
 * page), Cast, tabs counter, ⋮ menu. While typing, a → (Go) button appears; Enter works too.
 */
@Composable
private fun AddressBar(
    text: String,
    onHome: Boolean,
    cast: TvSession.State,
    /** Null = no page (home screen): no videos button. */
    videoCount: Int?,
    onVideos: () -> Unit,
    tabCount: Int,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCast: () -> Unit,
    onTabs: () -> Unit,
    onReload: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    val submit = {
        onSubmit()
        focusManager.clearFocus()
    }

    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Home screen: the search field sits under the logo (HomeScreen), only the icons stay here.
        if (onHome) Spacer(Modifier.weight(1f).height(56.dp)) else OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { editing = it.isFocused }
                .semantics { contentDescription = "Address bar" },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            placeholder = { Text("Search or type a website") },
            trailingIcon = when {
                editing -> ({ IconButton(onClick = submit) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Go") } })
                // Reload sits in the address bar (user choice); ★ add/remove lives in the ⋮ menu.
                !onHome -> ({ IconButton(onClick = onReload) { Icon(Icons.Filled.Refresh, contentDescription = "Reload", tint = CastifyColors.TextSecondary) } })
                else -> null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
        )
        // Tight icon row (user feedback): narrower buttons, full 48 dp height for touch. The
        // default 48x48 minimum would put the gaps back, so it is lifted for these three only.
        // Widths give the same visible gap between Cast, tabs counter and ⋮ (the ⋮ glyph is thin).
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            // Videos of the page (mockup option A, `docs/mockups/videos-button.html`): in the bar,
            // never over the page, where it used to hide the sites' own player buttons.
            if (videoCount != null) {
                IconButton(
                    onClick = onVideos,
                    modifier = Modifier.size(width = 44.dp, height = 48.dp).semantics {
                        contentDescription = if (videoCount == 0) "No video found" else "$videoCount video${if (videoCount > 1) "s" else ""} found"
                    },
                ) {
                    BadgedBox(badge = {
                        if (videoCount > 0) Badge(containerColor = CastifyColors.Red, contentColor = CastifyColors.TextPrimary) { Text("$videoCount") }
                    }) {
                        Icon(Icons.Filled.OndemandVideo, contentDescription = null, tint = CastifyColors.TextSecondary, modifier = Modifier.size(26.dp))
                    }
                }
            }
            IconButton(onClick = onCast, modifier = Modifier.size(width = 44.dp, height = 48.dp)) {
                CastIcon(cast)
            }
            IconButton(
                onClick = onTabs,
                modifier = Modifier.size(width = 38.dp, height = 48.dp).semantics { contentDescription = "$tabCount tabs" },
            ) {
                TabsCountIcon(tabCount)
            }
            menu()
        }
        Spacer(Modifier.width(6.dp))
    }
}

/**
 * Grey = no TV. Pulsing red = waiting for the TV's first answer. Red = connected and the TV
 * answers. Grey "connected" icon = connected but the TV stopped answering.
 */
@Composable
private fun CastIcon(cast: TvSession.State) {
    val name = cast.device?.name
    val modifier = Modifier.size(28.dp)
    when {
        name == null ->
            Icon(Icons.Filled.Cast, contentDescription = "Connect to a TV", tint = CastifyColors.TextSecondary, modifier = modifier)
        cast.connecting -> {
            val pulse by rememberInfiniteTransition(label = "connecting").animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                label = "connecting alpha",
            )
            Icon(Icons.Filled.Cast, contentDescription = "Connecting to $name", tint = CastifyColors.Red, modifier = modifier.alpha(pulse))
        }
        !cast.reachable ->
            Icon(Icons.Filled.CastConnected, contentDescription = "Can't reach $name", tint = CastifyColors.TextSecondary, modifier = modifier)
        else ->
            Icon(Icons.Filled.CastConnected, contentDescription = "Connected to $name", tint = CastifyColors.Red, modifier = modifier)
    }
}

private sealed interface SheetStep {
    data object Videos : SheetStep
    data class Quality(val video: DetectedVideo) : SheetStep
    data class Device(val video: DetectedVideo, val height: Int?) : SheetStep
}

@Composable
private fun CastSheet(
    viewModel: BrowserViewModel,
    videos: List<DetectedVideo>,
    connectedTo: TvDevice?,
    sendingTo: String?,
    onConnect: () -> Unit,
    onDone: () -> Unit,
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    var step by remember { mutableStateOf<SheetStep>(SheetStep.Videos) }

    /** Connected → send now. Not connected → the user always picks the TV (never auto-picked). */
    fun pickDevice(video: DetectedVideo, height: Int?) {
        if (connectedTo != null) {
            viewModel.send(video, height); onDone()
        } else {
            step = SheetStep.Device(video, height)
        }
    }

    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
        if (sendingTo != null) {
            Busy("Sending to $sendingTo…")
            return@Column
        }
        when (val current = step) {
            SheetStep.Videos -> if (videos.isEmpty()) {
                EmptyMessage("No video found", "Start the video in the page, then open this panel again.")
            } else {
                SheetTitle("Choose a video")
                LazyColumn {
                    items(videos, key = { it.stream.url }) { video ->
                        VideoCard(video) {
                            val qualities = video.details?.qualities.orEmpty()
                            if (qualities.size > 1) step = SheetStep.Quality(video) else pickDevice(video, null)
                        }
                        HorizontalDivider(color = CastifyColors.SurfaceRaised)
                    }
                }
            }

            is SheetStep.Quality -> {
                SheetTitle("Choose a quality")
                current.video.details?.qualities.orEmpty().forEach { quality ->
                    ListItem(
                        headlineContent = { Text(quality.label) },
                        colors = ListItemDefaults.colors(containerColor = CastifyColors.Surface),
                        modifier = Modifier.clickable { pickDevice(current.video, quality.height) },
                    )
                }
            }

            is SheetStep.Device -> DevicePicker(devices, onRefresh = viewModel::refreshDevices) { device ->
                onConnect()
                viewModel.send(current.video, current.height, device)
                onDone()
            }
        }
    }
}

/** Thumbnail + duration + quality. No URL: the user does not care. */
@Composable
private fun VideoCard(video: DetectedVideo, onClick: () -> Unit) {
    val details = video.details
    val duration = when {
        details == null -> "…"
        details.isLive -> "Live"
        details.durationMs != null -> formatDuration(details.durationMs!!)
        else -> "—"
    }
    val quality = when {
        details == null -> "…"
        else -> details.qualities.firstOrNull()?.label ?: "—"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(
            video.thumbnailUrl,
            video.stream.headers,
            Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(duration, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(quality, style = MaterialTheme.typography.bodyMedium, color = CastifyColors.TextSecondary)
        }
    }
}

/**
 * TV list. The search never stops while the list is open: with no TV yet, a spinner says
 * so (instead of an empty "not found"). Refresh restarts the network search from scratch.
 * Cast devices without the Castify app are listed dimmed, "App not launched", and do nothing
 * on tap; they turn ready by themselves when the app opens on the TV.
 */
@Composable
private fun DevicePicker(devices: List<NearbyTv>?, onRefresh: () -> Unit, onPick: (TvDevice) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SheetTitle("Play on") }
        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Search for TVs again") }
    }
    when {
        devices.isNullOrEmpty() -> {
            Busy("Searching for TVs on your Wi-Fi…")
            Text(
                "Open Castify on the TV and check both devices are on the same Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium,
                color = CastifyColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        else -> {
            devices.forEach { device -> key(device.id) { NearbyTvRow(device, onPick) } }
        }
    }
}

/** Option B of `docs/mockups/tv-picker-not-launched.html`: a status dot under every name. */
@Composable
private fun NearbyTvRow(device: NearbyTv, onPick: (TvDevice) -> Unit) {
    val castify = device.castify
    val ready = device.status == TvStatus.READY
    val dimmed = if (ready) 1f else 0.45f
    ListItem(
        leadingContent = {
            Icon(
                if (device.kind == DeviceKind.SPEAKER) Icons.Filled.Speaker else Icons.Filled.Tv,
                contentDescription = null,
                modifier = Modifier.alpha(dimmed),
            )
        },
        headlineContent = { Text(device.name, modifier = Modifier.alpha(dimmed)) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (ready) CastifyColors.ReadyDot else CastifyColors.Disabled),
                )
                Text(
                    when (device.status) {
                        TvStatus.READY -> "Ready"
                        TvStatus.NOT_RESPONDING -> "Not responding"
                        TvStatus.APP_NOT_LAUNCHED -> "App not launched"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ready) CastifyColors.TextSecondary else CastifyColors.Disabled,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = CastifyColors.Surface),
        modifier = if (castify != null) Modifier.clickable { onPick(castify) } else Modifier,
    )
}

@Composable
private fun Busy(text: String) = Row(
    Modifier.padding(24.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    CircularProgressIndicator(color = CastifyColors.Red)
    Text(text)
}

@Composable
private fun SheetTitle(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleLarge,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
)

@Composable
private fun EmptyMessage(title: String, body: String) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = CastifyColors.TextSecondary)
    }
}
