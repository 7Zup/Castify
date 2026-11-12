package app.castify.tv

import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionOff
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import app.castify.domain.model.PlaybackState
import app.castify.domain.model.PlaybackStats
import app.castify.domain.series.Episode
import app.castify.domain.series.SeriesInfo
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import app.castify.domain.status.PlaybackFailure
import app.castify.domain.status.TvPresence
import app.castify.domain.update.AppUpdater
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.filled.Cast
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.em

@Composable
fun TvScreenContent(viewModel: TvViewModel, surface: VideoSurface) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    CastifyTvTheme {
        Box(Modifier.fillMaxSize().background(TvColors.Video)) {
            when (state.screen) {
                TvScreen.WAITING -> {
                    Waiting(state.deviceName, state.presence)
                    Text(
                        state.versionLabel,
                        color = TvColors.Disabled,
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 28.dp, vertical = 20.dp),
                    )
                    if (state.updateDialogVisible) UpdateDialog(state.update, state.updateFocus, state.versionLabel)
                }
                TvScreen.PLAYING -> {
                    Video(surface)
                    if (state.stats.state == PlaybackState.BUFFERING) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center), color = TvColors.Red)
                    }
                    if (state.seekBubbleMs != 0L) {
                        SeekBubble(state.seekBubbleMs, Modifier.align(Alignment.Center).padding(bottom = 160.dp))
                    }
                    if (state.controls != null || state.timeBarVisible) {
                        TimeBar(state.stats, state.current?.title, state.controls, state.series != null, Modifier.align(Alignment.BottomCenter))
                    }
                    val series = state.series
                    if (state.nextVisible && series != null) {
                        series.next?.let { NextButton(it, state.nextCountdown, Modifier.align(Alignment.BottomEnd)) }
                    }
                    state.episodesPanel?.let { panel -> if (series != null) EpisodesPanel(series, panel, state.autoNext) }
                    state.episodeLoad?.let { EpisodeLoadCard(it) }
                    if (state.diagnosticsVisible) {
                        Diagnostics(state.stats, Modifier.align(Alignment.TopStart))
                    }
                    if (state.stopArmed) {
                        Text(
                            "Press Back again to stop the video",
                            color = TvColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 40.dp)
                                .background(TvColors.Panel.copy(alpha = 0.88f), RoundedCornerShape(50))
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                        )
                    }
                    state.subtitleMenu?.let { menu ->
                        SubtitleMenuPanel(menu, state.stats.selectedSubtitleId, Modifier.align(Alignment.CenterEnd))
                    }
                }
                TvScreen.ERROR -> PlaybackFailed(state)
            }
        }
    }
}

/**
 * Subtitle menu (▼), right side of the screen. Focus = white outline + slight scale
 * (design.md); the active choice has a check mark.
 */
@Composable
private fun SubtitleMenuPanel(menu: SubtitleMenuState, selectedId: String?, modifier: Modifier) {
    Column(
        modifier
            .padding(32.dp)
            .width(300.dp)
            .background(TvColors.Scrim, RoundedCornerShape(12.dp))
            .padding(vertical = 16.dp),
    ) {
        Text(
            "Subtitles",
            color = TvColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (menu.options.size <= 1) {
            Text(
                "No subtitles available",
                color = TvColors.TextSecondary,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            return@Column
        }
        // Keep the focused line in view when the list is long.
        val first = (menu.focused - VISIBLE_MENU_ROWS + 1).coerceIn(0, (menu.options.size - VISIBLE_MENU_ROWS).coerceAtLeast(0))
        menu.options.drop(first).take(VISIBLE_MENU_ROWS).forEachIndexed { offset, option ->
            val index = first + offset
            val focused = index == menu.focused
            Row(
                Modifier
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .fillMaxWidth()
                    .scale(if (focused) 1.04f else 1f)
                    .then(if (focused) Modifier.border(2.dp, TvColors.TextPrimary, RoundedCornerShape(8.dp)) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    option.name,
                    color = if (focused) TvColors.TextPrimary else TvColors.TextSecondary,
                    fontSize = 17.sp,
                    fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (option.trackId == selectedId) Text("✓", color = TvColors.Red, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private const val VISIBLE_MENU_ROWS = 8

/**
 * Bottom time bar (pause, or 3 s after a seek): title if known, `❚❚ Paused`, progress bar
 * (red = played, light gray = buffered, like the phone), elapsed left, total right.
 * Live: `Live`, no bar. Unknown duration: elapsed only.
 * With [controls] (▼, Netflix style): the bar can take the focus (white ring) and the button
 * row is shown below it.
 */
@Composable
private fun TimeBar(stats: PlaybackStats, title: String?, controls: ControlsState?, hasEpisodes: Boolean, modifier: Modifier) {
    // Layout "B" chosen by the user: compact content in a dark rounded panel at the bottom.
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 20.dp)
            .background(TvColors.Panel.copy(alpha = 0.88f), RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        title?.let {
            Text(it, color = TvColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
        if (stats.state == PlaybackState.PAUSED && controls == null) {
            Text(
                "❚❚  Paused",
                color = TvColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = if (title != null) 4.dp else 0.dp),
            )
        }
        val duration = stats.durationMs
        if (stats.isLive) {
            Text("Live", color = TvColors.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        } else {
            if (duration != null && duration > 0) {
                val played = (stats.positionMs.toFloat() / duration).coerceIn(0f, 1f)
                val buffered = ((stats.positionMs + stats.bufferedAheadMs).toFloat() / duration).coerceIn(played, 1f)
                val barFocused = controls?.focus == ControlFocus.PROGRESS
                Canvas(Modifier.fillMaxWidth().padding(top = 8.dp).height(16.dp)) {
                    val y = size.height / 2
                    val h = (if (barFocused) 4.dp else 3.dp).toPx()
                    val r = CornerRadius(h / 2)
                    drawRoundRect(TvColors.Surface, topLeft = Offset(0f, y - h / 2), size = Size(size.width, h), cornerRadius = r)
                    drawRoundRect(
                        TvColors.TextSecondary.copy(alpha = 0.6f),
                        topLeft = Offset(0f, y - h / 2),
                        size = Size(size.width * buffered, h),
                        cornerRadius = r,
                    )
                    drawRoundRect(TvColors.Red, topLeft = Offset(0f, y - h / 2), size = Size(size.width * played, h), cornerRadius = r)
                    val thumb = Offset(size.width * played, y)
                    if (barFocused) drawCircle(TvColors.TextPrimary, radius = 8.dp.toPx(), center = thumb)
                    drawCircle(TvColors.Red, radius = 6.dp.toPx(), center = thumb)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(formatTime(stats.positionMs), color = TvColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                duration?.takeIf { it > 0 }?.let { Text(formatTime(it), color = TvColors.TextSecondary, fontSize = 12.sp) }
            }
        }
        if (controls != null) ControlButtons(stats, controls.focus, hasEpisodes)
    }
}

/**
 * ⟲10 · Play/Pause · 10⟳ on the left, CC (and Episodes for a series) on the right (no Stop:
 * Back / Home stop). Focus = white filled circle + slight scale (design.md).
 */
@Composable
private fun ControlButtons(stats: PlaybackStats, focus: ControlFocus, hasEpisodes: Boolean) {
    val seekable = !stats.isLive && (stats.durationMs ?: 0) > 0
    val paused = stats.state == PlaybackState.PAUSED
    val subtitlesOn = stats.selectedSubtitleId != null
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
        if (seekable) ControlButton(Icons.Filled.Replay10, "Back 10 s", focus == ControlFocus.BACK_10)
        ControlButton(
            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            if (paused) "Play" else "Pause",
            focus == ControlFocus.PLAY_PAUSE,
        )
        if (seekable) ControlButton(Icons.Filled.Forward10, "Forward 10 s", focus == ControlFocus.FORWARD_10)
        Spacer(Modifier.weight(1f))
        ControlButton(
            if (subtitlesOn) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionOff,
            when {
                stats.subtitles.isEmpty() -> "No subtitles"
                subtitlesOn -> "Subtitles · On"
                else -> "Subtitles · Off"
            },
            focus == ControlFocus.SUBTITLES,
        )
        if (hasEpisodes) ControlButton(Icons.Filled.VideoLibrary, "Episodes", focus == ControlFocus.EPISODES)
    }
}

/**
 * `Next episode` bottom right (mockup T3), always focused: OK plays it, Back hides it. With
 * Auto next on, a countdown at the very end (T4).
 */
@Composable
private fun NextButton(next: Episode, countdown: Int?, modifier: Modifier) {
    Row(
        modifier
            .padding(40.dp)
            .border(3.dp, TvColors.TextPrimary.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(3.dp)
            .background(TvColors.TextPrimary, RoundedCornerShape(8.dp))
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = TvColors.Video, modifier = Modifier.size(28.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                if (countdown != null) "Next episode in $countdown s" else "Next episode",
                color = TvColors.Video,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                listOfNotNull(next.number.label, next.title).joinToString(" · "),
                color = TvColors.Surface,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * Episodes panel (mockup T2): season chips, the season's episodes, Auto next. The focused row
 * shows the focus (white outline + slight scale); the playing episode has a red badge.
 */
@Composable
private fun EpisodesPanel(series: SeriesInfo, panel: EpisodesPanelState, autoNext: Boolean) {
    val show = series.show
    Column(
        Modifier
            .fillMaxSize()
            .background(TvColors.Scrim)
            // TV safe area: nothing near the bottom edge (some TVs crop it).
            .padding(start = 56.dp, end = 56.dp, top = 40.dp, bottom = 56.dp),
    ) {
        Text(series.showName, color = TvColors.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        if (series.episodes.isEmpty()) {
            Text("Episode list unavailable", color = TvColors.TextSecondary, fontSize = 18.sp, modifier = Modifier.padding(top = 16.dp))
        } else {
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                show.seasons.forEach { season ->
                    val selected = season == panel.season
                    val focused = selected && panel.row == EpisodesRow.SEASONS
                    Text(
                        "Season $season",
                        color = if (selected) TvColors.Video else TvColors.TextSecondary,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal,
                        modifier = Modifier
                            .scale(if (focused) 1.08f else 1f)
                            .then(if (focused) Modifier.border(2.dp, TvColors.TextPrimary, RoundedCornerShape(50)).padding(3.dp) else Modifier)
                            .background(if (selected) TvColors.TextPrimary else TvColors.Surface, RoundedCornerShape(50))
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                    )
                }
            }
            val episodes = show.episodesOf(panel.season)
            // A window of cards that keeps the focused one in view.
            val first = (panel.episodeIndex - 2).coerceIn(0, (episodes.size - VISIBLE_EPISODES).coerceAtLeast(0))
            Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                episodes.drop(first).take(VISIBLE_EPISODES).forEachIndexed { offset, episode ->
                    val focused = panel.row == EpisodesRow.EPISODES && first + offset == panel.episodeIndex
                    EpisodeCard(episode, playing = episode.number == series.current, focused = focused)
                }
            }
        }
        val focused = panel.row == EpisodesRow.AUTO_NEXT
        Row(
            Modifier
                .padding(top = 32.dp)
                .scale(if (focused) 1.05f else 1f)
                .then(if (focused) Modifier.border(2.dp, TvColors.TextPrimary, RoundedCornerShape(8.dp)) else Modifier)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .border(2.dp, if (autoNext) TvColors.Red else TvColors.TextSecondary, RoundedCornerShape(4.dp))
                    .background(if (autoNext) TvColors.Red else Color.Transparent, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (autoNext) Text("✓", color = TvColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text("Auto next", color = TvColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
            Text("plays the next one at the end", color = TvColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

private const val VISIBLE_EPISODES = 5

@Composable
private fun EpisodeCard(episode: Episode, playing: Boolean, focused: Boolean) {
    Column(Modifier.width(200.dp).scale(if (focused) 1.06f else 1f)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .then(if (focused) Modifier.border(3.dp, TvColors.TextPrimary, RoundedCornerShape(6.dp)) else Modifier)
                .background(TvColors.Surface, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("E${episode.number.episode}", color = TvColors.TextSecondary, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            // The episode's picture (TVmaze) over the number, which stays as the placeholder.
            episode.imageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().clip(RoundedCornerShape(6.dp)),
                )
            }
            if (playing) {
                Text(
                    "PLAYING",
                    color = TvColors.TextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(TvColors.Red, RoundedCornerShape(3.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            listOfNotNull(episode.number.episode.toString(), episode.title).joinToString(" · "),
            color = TvColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            modifier = Modifier.padding(top = 8.dp),
        )
        episode.runtimeMin?.let { Text("$it min", color = TvColors.TextSecondary, fontSize = 12.sp) }
    }
}

/** An episode the phone is finding (mockup T5), then Retry / Back if it could not. */
@Composable
private fun EpisodeLoadCard(load: EpisodeLoad) {
    Box(Modifier.fillMaxSize().background(TvColors.Scrim), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (!load.failed) {
                CircularProgressIndicator(color = TvColors.Red)
                Text("Loading ${load.number.label}…", color = TvColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 20.dp))
                Text("Your phone is finding the video", color = TvColors.TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
            } else {
                Text("Couldn't load ${load.number.label}", color = TvColors.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text("Check your phone is on and on the same Wi-Fi", color = TvColors.TextSecondary, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ErrorButtonView("Retry", load.focus == ErrorButton.RETRY)
                    ErrorButtonView("Back", load.focus == ErrorButton.HOME)
                }
            }
        }
    }
}

/** Compact button (layout "B"): the label only shows under the focused button, like Netflix. */
@Composable
private fun ControlButton(icon: ImageVector, label: String, focused: Boolean) {
    Column(
        Modifier.width(44.dp).scale(if (focused) 1.1f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .background(if (focused) TvColors.TextPrimary else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (focused) TvColors.Background else TvColors.TextPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        // Fixed height so the row does not jump when the focus moves.
        Box(Modifier.height(20.dp).wrapContentWidth(unbounded = true), contentAlignment = Alignment.Center) {
            if (focused) {
                Text(
                    label,
                    color = TvColors.TextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** "+30 s" / "−20 s" while ◀ / ▶ are pressed (same rule as the phone remote). */
@Composable
private fun SeekBubble(totalMs: Long, modifier: Modifier) {
    val seconds = totalMs / 1000
    Text(
        if (seconds > 0) "+$seconds s" else "−${-seconds} s",
        color = TvColors.TextPrimary,
        fontSize = 26.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = modifier
            .background(TvColors.Scrim, RoundedCornerShape(12.dp))
            .padding(horizontal = 28.dp, vertical = 12.dp),
    )
}

@Composable
private fun Video(surface: VideoSurface) {
    AndroidView(
        factory = { context -> PlayerView(context).apply { keepScreenOn = true }.also(surface.attach) },
        modifier = Modifier.fillMaxSize(),
    )
    DisposableEffect(Unit) { onDispose { surface.detach() } }
}

/**
 * Welcome screen (mockup `docs/mockups/offline-errors.html`, T3–T5): logo + title + TV name
 * pill on the left; three steps on the right. Each state checks the steps the TV really knows
 * are done (green check): Offline = none, Standby = Wi-Fi, Connected = Wi-Fi + phone.
 */
@Composable
private fun Waiting(deviceName: String, presence: TvPresence) {
    Row(Modifier.fillMaxSize().background(TvColors.Background)) {
        Column(
            Modifier.fillMaxHeight().width(410.dp).background(TvColors.Panel),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.castify_mark), contentDescription = "Castify", modifier = Modifier.size(170.dp))
            Text(
                if (presence == TvPresence.OFFLINE) "Not connected" else "Ready to cast",
                color = TvColors.TextPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(top = 16.dp),
            )
            PresencePill(deviceName, presence, Modifier.padding(top = 20.dp))
        }
        Column(
            Modifier.fillMaxHeight().weight(1f).padding(start = 70.dp, end = 48.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            val title = when (presence) {
                TvPresence.OFFLINE -> "Connect this TV to Wi-Fi"
                TvPresence.STANDBY -> "Watch a video on this TV"
                TvPresence.CONNECTED -> "Pick a video on your phone"
            }
            Text(title, color = TvColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            val onWifi = presence != TvPresence.OFFLINE
            val phone = presence == TvPresence.CONNECTED
            Step(
                1, "Connect this TV to Wi-Fi", "Settings → Network & Internet, same Wi-Fi as your phone",
                done = onWifi, enabled = true, Modifier.padding(top = 40.dp),
            )
            Step(
                2, "Open Castify on your phone, tap [cast] and pick $deviceName", "Same Wi-Fi as this TV",
                done = phone, enabled = onWifi, Modifier.padding(top = 32.dp),
            )
            Step(
                3, "Find a video, tap the video icon at the top", "Once the video plays, you can lock your phone and enjoy",
                done = false, enabled = onWifi, Modifier.padding(top = 32.dp),
            )
        }
    }
}

/** TV name + state: white pulsing dot Standby, green Connected, red Offline (design.md). */
@Composable
private fun PresencePill(deviceName: String, presence: TvPresence, modifier: Modifier) {
    val pulse = rememberInfiniteTransition(label = "standby")
    val alpha by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(1_000), RepeatMode.Reverse), label = "standby-alpha",
    )
    val (color, label) = when (presence) {
        TvPresence.OFFLINE -> TvColors.Red to "Offline"
        TvPresence.STANDBY -> TvColors.TextPrimary.copy(alpha = alpha) to "Standby"
        TvPresence.CONNECTED -> TvColors.Ready to "Connected"
    }
    Row(
        modifier
            .background(TvColors.Surface, RoundedCornerShape(50))
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(
            deviceName,
            color = TvColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 12.dp),
        )
        Text(label, color = TvColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 24.dp))
    }
}

/** A step: red number to do, green ✓ done (user choice), dimmed while it can't be done yet. */
@Composable
private fun Step(number: Int, title: String, detail: String, done: Boolean, enabled: Boolean, modifier: Modifier) {
    Row(modifier.alpha(if (enabled || done) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).background(if (done) TvColors.Ready else TvColors.Red, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (done) "✓" else number.toString(),
                color = if (done) TvColors.Video else TvColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        Column(Modifier.padding(start = 20.dp)) {
            // "[cast]" in a title is drawn as the real Cast icon (the one on the phone's top bar).
            val color = if (done) TvColors.TextSecondary else TvColors.TextPrimary
            val text = buildAnnotatedString {
                val parts = title.split("[cast]")
                parts.forEachIndexed { i, part ->
                    append(part)
                    if (i < parts.lastIndex) appendInlineContent("cast", "Cast")
                }
            }
            val icons = mapOf(
                "cast" to InlineTextContent(Placeholder(1.2.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                    Icon(Icons.Filled.Cast, contentDescription = "Cast", tint = color, modifier = Modifier.fillMaxSize())
                },
            )
            Text(text, inlineContent = icons, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (!done) Text(detail, color = TvColors.TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * Error screen (mockup T1 / T2): plain words, Retry (restarts where the video stopped) and
 * Home (stops, welcome screen). ◀ ▶ move the focus, OK presses, Back = Home. The error code
 * stays small at the bottom right.
 */
@Composable
private fun PlaybackFailed(state: TvUiState) {
    val error = state.stats.error
    val lost = PlaybackFailure.of(error) == PlaybackFailure.CONNECTION_LOST
    Box(Modifier.fillMaxSize().background(TvColors.Background)) {
        Column(
            Modifier.align(Alignment.Center).padding(64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(84.dp).background(TvColors.Surface, CircleShape), contentAlignment = Alignment.Center) {
                Text(if (lost) "⊘" else "!", color = TvColors.TextSecondary, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text(
                if (lost) "Connection lost" else "This video can't be played",
                color = TvColors.TextPrimary,
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                if (lost) "The TV lost its internet connection. Check the Wi-Fi, then try again."
                else "The website refused to send the video. Try again, or send it again from your phone.",
                color = TvColors.TextSecondary,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 12.dp).widthIn(max = 640.dp),
            )
            Row(Modifier.padding(top = 36.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ErrorButtonView("Retry", state.errorFocus == ErrorButton.RETRY)
                ErrorButtonView("Home", state.errorFocus == ErrorButton.HOME)
            }
        }
        val code = buildString {
            state.current?.title?.let { append(it).append(" · ") }
            if (state.lastPositionMs > 0) append("stopped at ${formatTime(state.lastPositionMs)} · ")
            if (error?.httpStatus != null) append("HTTP ${error.httpStatus}") else append(error?.code ?: "Unknown error")
        }
        Text(code, color = TvColors.Disabled, fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp))
    }
}

/** TV focus style (design.md): white fill + slight scale on the focused button. */
@Composable
private fun ErrorButtonView(label: String, focused: Boolean) {
    Text(
        label,
        color = if (focused) TvColors.Video else TvColors.TextSecondary,
        fontSize = 20.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier
            .scale(if (focused) 1.06f else 1f)
            .background(if (focused) TvColors.TextPrimary else TvColors.Surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 36.dp, vertical = 14.dp),
    )
}

@Composable
private fun Diagnostics(stats: PlaybackStats, modifier: Modifier) {
    val lines = listOf(
        "State" to stats.state.name.lowercase(),
        "Position" to "${formatTime(stats.positionMs)} / ${stats.durationMs?.let(::formatTime) ?: "live / unknown"}",
        "Buffered ahead" to "%.1f s".format(stats.bufferedAheadMs / 1000.0),
        "Bandwidth" to (stats.bandwidthKbps?.let { "%.1f Mbps".format(it / 1000.0) } ?: "—"),
        "Rate cap" to (stats.rateCapKbps?.let { "%.0f Mbps".format(it / 1000.0) } ?: "—"),
        "Rebuffers" to stats.rebufferCount.toString(),
        "Format" to (stats.format ?: "—"),
    )
    Column(
        modifier
            .padding(32.dp)
            .background(TvColors.Scrim, RoundedCornerShape(12.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Diagnostics · Up to hide", color = TvColors.Red, fontSize = 16.sp)
        lines.forEach { (label, value) ->
            Text("$label: $value", color = TvColors.TextPrimary, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Update dialog over the welcome screen (mockup `docs/mockups/app-update.html`, T1–T3).
 * Focus = white fill + slight scale, like the error screen buttons.
 */
@Composable
private fun UpdateDialog(update: AppUpdater.State, focus: UpdateButton, current: String) {
    Box(Modifier.fillMaxSize().background(TvColors.Scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .background(TvColors.Surface, RoundedCornerShape(16.dp))
                .padding(horizontal = 48.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (update) {
                is AppUpdater.State.Available -> {
                    DialogTitle("Update available")
                    DialogBody("Castify ${update.update.version.label} is ready to install. You have $current.")
                    Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        ErrorButtonView("Update", focus == UpdateButton.PRIMARY)
                        ErrorButtonView("Not now", focus == UpdateButton.NOT_NOW)
                    }
                }
                is AppUpdater.State.Downloading -> {
                    DialogTitle("Downloading ${update.update.version.label}")
                    val fraction = if (update.totalBytes > 0) (update.downloadedBytes.toFloat() / update.totalBytes).coerceIn(0f, 1f) else 0f
                    Box(Modifier.padding(top = 28.dp).width(480.dp).height(6.dp).background(TvColors.Disabled, RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(TvColors.Red, RoundedCornerShape(3.dp)))
                    }
                    Text(
                        "%.1f / %.1f MB".format(update.downloadedBytes / 1_000_000.0, update.totalBytes / 1_000_000.0),
                        color = TvColors.TextSecondary,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(top = 10.dp).width(480.dp).wrapContentWidth(Alignment.End),
                    )
                    Row(Modifier.padding(top = 24.dp)) { ErrorButtonView("Cancel", focused = true) }
                }
                is AppUpdater.State.Failed -> {
                    DialogTitle("Update failed")
                    DialogBody("Couldn't download ${update.update.version.label}. Check the Wi-Fi and try again.")
                    Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        ErrorButtonView("Retry", focus == UpdateButton.PRIMARY)
                        ErrorButtonView("Not now", focus == UpdateButton.NOT_NOW)
                    }
                }
                AppUpdater.State.Idle, is AppUpdater.State.WaitingPermission, is AppUpdater.State.Installing -> Unit
            }
        }
    }
}

@Composable
private fun DialogTitle(text: String) =
    Text(text, color = TvColors.TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)

@Composable
private fun DialogBody(text: String) =
    Text(text, color = TvColors.TextSecondary, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp))
