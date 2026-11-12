package app.castify.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animate
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.TransformOrigin
import kotlinx.coroutines.Job
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.castify.domain.model.NowPlaying
import app.castify.domain.model.PlaybackState
import app.castify.domain.playback.PlayerCommand
import app.castify.domain.playback.RemoteControl
import app.castify.domain.session.TvSession
import kotlinx.coroutines.delay

/**
 * The now-playing bar and the full remote are one surface: 0 = bar, 1 = full remote. The
 * chevrons (⌃ on the bar, ⌄ on the remote) animate it with a spring. No drag (user choice).
 */
@Stable
class RemoteExpansion internal constructor(private val scope: CoroutineScope) {
    /**
     * Plain state written by [animate]: frames of the move only redraw (see [ExpandingPlayer]).
     */
    private var current by mutableFloatStateOf(0f)
    private var animation: Job? = null

    /** Height of the mini player: where the growing surface starts. */
    internal var barHeightPx by mutableFloatStateOf(0f)

    /** 0 = bar, 1 = full remote. Read it in draw / layout lambdas, not in composition. */
    val value: Float get() = current

    /** Only flips at 0: reading it in composition does not recompose every frame. */
    val visible: Boolean by derivedStateOf { current > 0f }

    /** The bar's controls are still (partly) visible, in the first third of the move. */
    internal val barShown: Boolean by derivedStateOf { current < BAR_FADE_END }

    fun open() = animateTo(1f, 0f)
    fun close() = animateTo(0f, 0f)
    fun snapClosed() { animation?.cancel(); current = 0f }

    private fun animateTo(target: Float, velocity: Float) {
        animation?.cancel()
        animation = scope.launch {
            animate(current, target, velocity, SPRING) { v, _ -> current = v.coerceIn(0f, 1f) }
        }
    }

    private companion object {
        val SPRING = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    }
}

@Composable
fun rememberRemoteExpansion(): RemoteExpansion {
    val scope = rememberCoroutineScope()
    val expansion = remember { RemoteExpansion(scope) }
    return expansion
}

/**
 * Centered chevron on top of the surface: ⌃ on the bar, ⌄ on the remote (user's pick over the
 * drag handle). Not a button of its own: the whole strip around it is the tap target and
 * highlights as one (user rule).
 */
@Composable
private fun Chevron(up: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(CHEVRON_HEIGHT), contentAlignment = Alignment.Center) {
        Icon(
            if (up) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = CastifyColors.TextSecondary,
            modifier = Modifier.size(CHEVRON_HEIGHT),
        )
    }
}

/**
 * Bottom bar while the connected TV plays something: thumbnail, play/pause, stop and a
 * draggable progress bar, usable without opening the full remote. A ⌃ on top, the thumbnail
 * or the text grow it into the full remote ([ExpandingPlayer]). While it grows, [ExpandingPlayer] draws the bar in its place.
 */
@Composable
fun NowPlayingBar(
    tv: TvSession.State,
    expansion: RemoteExpansion,
    onCommand: (PlayerCommand) -> Unit,
    onSubtitles: () -> Unit,
) {
    val now = tv.nowPlaying ?: return
    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { expansion.barHeightPx = it.height.toFloat() }
            .graphicsLayer { alpha = if (expansion.visible) 0f else 1f }
            .background(CastifyColors.Surface),
    ) {
        BarContent(tv, now, expansion::open, onCommand, onSubtitles, showThumbnail = true)
    }
}

/** ⌃, thumbnail (or its empty slot), TV name + time, CC / play-pause / stop, progress. */
@Composable
private fun BarContent(
    tv: TvSession.State,
    now: NowPlaying,
    onOpen: () -> Unit,
    onCommand: (PlayerCommand) -> Unit,
    onSubtitles: () -> Unit,
    showThumbnail: Boolean,
) {
    // The whole bar is one tap target (one highlight); its buttons and progress bar keep theirs.
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open the remote", onClick = onOpen)
            .navigationBarsPadding()
            .padding(start = BAR_PADDING, end = BAR_PADDING, bottom = 4.dp),
    ) {
        Chevron(up = true)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val slot = Modifier.width(BAR_THUMB_WIDTH).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp))
                if (showThumbnail) Thumbnail(now.thumbnailUrl, now.thumbnailHeaders, slot) else Spacer(slot)
                Column(Modifier.weight(1f)) {
                    Text(tv.device?.name.orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (!tv.reachable) "Not reachable" else progressLabel(now),
                        style = MaterialTheme.typography.bodySmall,
                        color = CastifyColors.TextSecondary,
                    )
                }
            }
            SubtitlesButton(now, onSubtitles, size = 44)
            PlayPauseButton(now, onCommand, size = 44)
            StopButton(onCommand, size = 44)
        }
        if (tv.reachable) Progress(now, onCommand, showTimes = false)
    }
}

/**
 * The mini player growing into the full remote (and back), following [expansion]:
 * - the surface's top edge rises from the bar to the top of the screen;
 * - the thumbnail moves and grows from its place in the bar to its place in the remote;
 * - the bar's text and buttons fade out early, the remote's controls fade in late.
 * Composed only while [RemoteExpansion.visible]. It takes every touch: nothing of the browser
 * underneath (address bar) can be reached. Tapping the remote's top bar, Back or dragging
 * down shrinks it back to the bar.
 */
@Composable
fun ExpandingPlayer(
    tv: TvSession.State,
    expansion: RemoteExpansion,
    onCommand: (PlayerCommand) -> Unit,
    onRetry: () -> Unit,
    onSubtitles: () -> Unit,
) {
    val onClose: () -> Unit = expansion::close
    BackHandler(onBack = onClose)
    val now = tv.nowPlaying ?: NowPlaying.IDLE
    val density = LocalDensity.current
    var remoteThumb by remember { mutableStateOf<Rect?>(null) }

    // Everything below reads expansion.value only in layout / draw lambdas: a frame of the move
    // redraws, it never recomposes (that was the lag).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullHeight = constraints.maxHeight.toFloat()
        val fullWidth = constraints.maxWidth.toFloat()
        /** Top edge of the surface, in px from the top of the screen. */
        fun top(): Float = lerp(fullHeight - expansion.barHeightPx.coerceIn(1f, fullHeight), 0f, expansion.value)

        // 1. The surface: grows from the bar, takes drags (resize) and every tap on empty areas.
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(lerp(CastifyColors.Surface, CastifyColors.Background, expansion.value), topLeft = Offset(0f, top()))
                }
                .pointerInput(Unit) { detectTapGestures { } },
        )

        // 2. The thumbnail. Loaded once at its full size (sharp when big) and scaled down to the
        // bar slot. Its distance to the top edge shrinks from the bar's to the remote's, so it
        // always stays inside the growing surface.
        val to = remoteThumb ?: with(density) {
            val width = fullWidth - 2 * REMOTE_PADDING.toPx()
            Rect(Offset(REMOTE_PADDING.toPx(), 120.dp.toPx()), Size(width, width * 9f / 16f))
        }
        val barLeft = with(density) { BAR_PADDING.toPx() }
        val barTopInset = with(density) { CHEVRON_HEIGHT.toPx() }
        val barWidth = with(density) { BAR_THUMB_WIDTH.toPx() }
        Thumbnail(
            now.thumbnailUrl,
            now.thumbnailHeaders,
            Modifier
                .offset { IntOffset(to.left.roundToInt(), to.top.roundToInt()) }
                .size(with(density) { to.width.toDp() }, with(density) { to.height.toDp() })
                .graphicsLayer {
                    val f = expansion.value
                    val scale = lerp(barWidth / to.width, 1f, f)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = lerp(barLeft, to.left, f) - to.left
                    translationY = top() + lerp(barTopInset, to.top, f) - to.top
                    // Rounded 4 dp at every size (the scale would shrink a fixed corner).
                    shape = RoundedCornerShape((4.dp.toPx() / scale).toDp())
                    clip = true
                },
        )

        // 3. The remote's controls, laid out full screen once, shown in the last half of the move.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = ((expansion.value - 0.5f) / 0.5f).coerceIn(0f, 1f) }
                .drawWithContent { clipRect(top = top()) { this@drawWithContent.drawContent() } },
        ) {
            RemoteContent(tv, now, onClose, onCommand, onRetry, onSubtitles) { remoteThumb = it }
        }

        // 4. The bar's text and buttons, riding the top edge, gone in the first third of the move.
        // Removed once invisible: a transparent bar still takes taps, and at the top of the open
        // remote its progress bar caught taps on the title (seek) and on ⌄ (could not close).
        if (tv.nowPlaying != null && expansion.barShown) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, top().roundToInt()) }
                    .graphicsLayer { alpha = (1f - expansion.value / BAR_FADE_END).coerceIn(0f, 1f) },
            ) {
                BarContent(tv, now, expansion::open, onCommand, onSubtitles, showThumbnail = false)
            }
        }
    }
}

/**
 * The full remote's controls: top bar (tap = shrink), thumbnail slot (spinner, seek bubble;
 * the thumbnail itself is drawn by [ExpandingPlayer]), progress, −10 s / play-pause / stop /
 * +10 s. [onThumbnailPlaced] reports where the thumbnail goes, in screen coordinates.
 */
@Composable
private fun RemoteContent(
    tv: TvSession.State,
    now: NowPlaying,
    onClose: () -> Unit,
    onCommand: (PlayerCommand) -> Unit,
    onRetry: () -> Unit,
    onSubtitles: () -> Unit,
    onThumbnailPlaced: (Rect) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = REMOTE_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Chevron + TV name: one tap target with one highlight (user rule); CC keeps its own.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Shrink the remote", onClick = onClose),
        ) {
            // A hair lower than the old handle (user).
            Chevron(up = false, Modifier.padding(top = 8.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tv.device?.name.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (tv.reachable) SubtitlesButton(now, onSubtitles, size = 48)
            }
        }

        // Repeated ±10 s presses add up in one bubble ("+10 s", "+20 s"…), reset after a pause.
        var seekTotalMs by remember { mutableStateOf(0L) }
        var seekPresses by remember { mutableStateOf(0) }
        LaunchedEffect(seekPresses) {
            if (seekPresses > 0) {
                delay(SEEK_BUBBLE_MS)
                seekTotalMs = 0
            }
        }
        val seek: (Long) -> Unit = { delta ->
            seekTotalMs = if (seekTotalMs != 0L && (seekTotalMs > 0) != (delta > 0)) delta else seekTotalMs + delta
            seekPresses++
            onCommand(PlayerCommand.SeekBy(delta))
        }

        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onGloballyPositioned { onThumbnailPlaced(it.boundsInRoot()) },
            contentAlignment = Alignment.Center,
        ) {
            if (now.state == PlaybackState.BUFFERING && tv.reachable) CircularProgressIndicator(color = CastifyColors.Red)
            if (seekTotalMs != 0L) SeekBubble(seekTotalMs)
        }
        Spacer(Modifier.height(32.dp))

        if (!tv.reachable) {
            Text("${tv.device?.name ?: "The TV"} is not reachable", style = MaterialTheme.typography.titleMedium)
            Text(
                "Check the TV is on and on the same Wi-Fi.",
                color = CastifyColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("Retry") }
            return@Column
        }

        Progress(now, onCommand, showTimes = true)
        Spacer(Modifier.height(24.dp))
        // All controls on one line: −10 s, play/pause, stop, +10 s.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = { seek(-RemoteControl.SEEK_STEP_MS) }, enabled = !now.isLive, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds", modifier = Modifier.size(40.dp))
            }
            PlayPauseButton(now, onCommand, size = 72)
            StopButton(onCommand, size = 56)
            IconButton(onClick = { seek(RemoteControl.SEEK_STEP_MS) }, enabled = !now.isLive, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds", modifier = Modifier.size(40.dp))
            }
        }
    }
}

private val BAR_PADDING = 12.dp
/** The bar's controls are gone after this share of the move. */
private const val BAR_FADE_END = 0.3f
private val BAR_THUMB_WIDTH = 96.dp
private val CHEVRON_HEIGHT = 24.dp
private val REMOTE_PADDING = 24.dp

private const val SEEK_BUBBLE_MS = 1_200L

@Composable
private fun BufferedTrack(played: Float, buffered: Float) {
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(CastifyColors.SurfaceRaised, cornerRadius = radius)
        drawRoundRect(
            CastifyColors.TextSecondary.copy(alpha = 0.6f),
            size = Size(size.width * maxOf(buffered, played), size.height),
            cornerRadius = radius,
        )
        drawRoundRect(CastifyColors.Red, size = Size(size.width * played, size.height), cornerRadius = radius)
    }
}

/** "+30 s" / "−20 s" over the thumbnail while the user taps ±10 s. */
@Composable
private fun SeekBubble(totalMs: Long) {
    val seconds = totalMs / 1000
    Text(
        if (seconds > 0) "+$seconds s" else "−${-seconds} s",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(CastifyColors.Background.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .semantics { contentDescription = "Seek ${if (seconds > 0) "forward" else "back"} ${kotlin.math.abs(seconds)} seconds" },
    )
}

@Composable
private fun StopButton(onCommand: (PlayerCommand) -> Unit, size: Int) {
    IconButton(onClick = { onCommand(PlayerCommand.Stop) }, modifier = Modifier.size(size.dp)) {
        Icon(Icons.Filled.Stop, contentDescription = "Stop", modifier = Modifier.size((size * 0.65).dp))
    }
}

/** Progress bar with the TV buffer; dragging only seeks when released. Live: no bar. */
@OptIn(ExperimentalMaterial3Api::class) // Slider custom track
@Composable
private fun Progress(now: NowPlaying, onCommand: (PlayerCommand) -> Unit, showTimes: Boolean) {
    val duration = now.durationMs
    if (now.isLive || duration == null || duration <= 0) {
        if (showTimes) Text(if (now.isLive) "Live" else formatDuration(now.positionMs), color = CastifyColors.TextSecondary)
        return
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: (now.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val buffered = (now.bufferedPositionMs.toFloat() / duration).coerceIn(0f, 1f)
    Slider(
        value = fraction,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { onCommand(PlayerCommand.SeekTo((it * duration).toLong())) }
            dragging = null
        },
        colors = SliderDefaults.colors(thumbColor = CastifyColors.Red),
        // YouTube-style track: dark rest, light gray = downloaded by the TV, red = played.
        track = { BufferedTrack(played = fraction, buffered = buffered) },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playback position" },
    )
    if (showTimes) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration((fraction * duration).toLong()), color = CastifyColors.TextSecondary)
            Text(formatDuration(duration), color = CastifyColors.TextSecondary)
        }
    }
}

@Composable
private fun PlayPauseButton(now: NowPlaying, onCommand: (PlayerCommand) -> Unit, size: Int) {
    val paused = now.state == PlaybackState.PAUSED
    FilledIconButton(
        onClick = { onCommand(PlayerCommand.TogglePause) },
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = CastifyColors.TextPrimary, contentColor = CastifyColors.Background),
        modifier = Modifier.size(size.dp),
    ) {
        Icon(
            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = if (paused) "Play" else "Pause",
            modifier = Modifier.size((size * 0.55).dp),
        )
    }
}

private fun progressLabel(now: NowPlaying): String = when {
    now.state == PlaybackState.BUFFERING -> "Loading…"
    now.isLive -> "Live"
    now.durationMs != null -> "${formatDuration(now.positionMs)} / ${formatDuration(now.durationMs!!)}"
    else -> formatDuration(now.positionMs)
}
