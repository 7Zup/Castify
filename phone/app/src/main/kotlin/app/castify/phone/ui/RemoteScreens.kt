package app.castify.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.clickable
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
 * Bottom bar while the connected TV plays something: thumbnail, play/pause, stop and a
 * draggable progress bar, usable without opening the full remote. Tapping the thumbnail
 * or the text opens the full remote (±10 s).
 */
@Composable
fun NowPlayingBar(
    tv: TvSession.State,
    onOpen: () -> Unit,
    onCommand: (PlayerCommand) -> Unit,
    onSubtitles: () -> Unit,
) {
    val now = tv.nowPlaying ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .background(CastifyColors.Surface)
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.weight(1f).clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Thumbnail(now.thumbnailUrl, now.thumbnailHeaders, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)))
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

/** Full-screen remote: thumbnail, progress, −10 s / play-pause / +10 s, stop. */
@Composable
fun RemoteScreen(
    tv: TvSession.State,
    onClose: () -> Unit,
    onCommand: (PlayerCommand) -> Unit,
    onRetry: () -> Unit,
    onSubtitles: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val now = tv.nowPlaying ?: NowPlaying.IDLE

    Column(
        Modifier
            .fillMaxSize()
            .background(CastifyColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close remote") }
            Text(
                tv.device?.name.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            if (tv.reachable) SubtitlesButton(now, onSubtitles, size = 48)
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
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            Thumbnail(now.thumbnailUrl, now.thumbnailHeaders, Modifier.fillMaxSize())
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
