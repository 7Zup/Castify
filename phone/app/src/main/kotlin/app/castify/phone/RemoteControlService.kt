package app.castify.phone

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.castify.domain.model.PlaybackState
import app.castify.domain.playback.PlayerCommand
import app.castify.domain.playback.RemoteControl
import app.castify.domain.session.TvSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Media notification + lock screen controls for the video playing on the connected TV.
 *
 * Presentation only: the Media3 session drives a [RemoteTvPlayer], which mirrors
 * [TvSession] state and turns button presses into domain [PlayerCommand]s.
 */
@OptIn(UnstableApi::class)
class RemoteControlService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val session = (application as CastifyApp).container.tvSession
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val back10 = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setDisplayName("Back 10 seconds")
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val forward10 = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setDisplayName("Forward 10 seconds")
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
        mediaSession = MediaSession.Builder(this, RemoteTvPlayer(session, scope))
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(ImmutableList.of(back10, forward10))
            .build()
            // The service is started with startService (no controller binds to it), so the
            // session must be added by hand: otherwise Media3 never posts the notification nor
            // goes foreground, and Android stops the service ~1 min after the app leaves the
            // screen ("Stopping service due to app idle", seen 11/05/2026). The system media
            // card then showed another app's player instead of the TV.
            .also(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        /** Starting from the background can be refused by Android: the notification then shows next time the app is opened. */
        fun start(context: Context) {
            runCatching { context.startService(Intent(context, RemoteControlService::class.java)) }
                .onFailure { Log.w("Castify", "Media notification not started: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteControlService::class.java))
        }
    }
}

/** A Media3 [Player] that is only a remote control for the TV. */
@OptIn(UnstableApi::class)
private class RemoteTvPlayer(
    private val session: TvSession,
    scope: CoroutineScope,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var snapshot: TvSession.State = session.state.value

    init {
        scope.launch {
            session.state.collect {
                snapshot = it
                invalidateState()
            }
        }
    }

    override fun getState(): State {
        val device = snapshot.device
        val now = snapshot.nowPlaying
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE,
            )
            .apply {
                if (now != null && !now.isLive) {
                    addAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                }
            }
            .build()
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setSeekBackIncrementMs(RemoteControl.SEEK_STEP_MS)
            .setSeekForwardIncrementMs(RemoteControl.SEEK_STEP_MS)

        if (device == null || now == null || !now.isActive) {
            return builder.setPlaybackState(Player.STATE_IDLE).setPlaylist(emptyList()).build()
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(now.title ?: "Playing on ${device.name}")
            .setArtist("Castify · ${device.name}")
            .apply { now.thumbnailUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        val item = MediaItemData.Builder("tv")
            .setMediaItem(MediaItem.Builder().setMediaId("tv").setMediaMetadata(metadata).build())
            .setDurationUs(now.durationMs?.let { it * 1_000 } ?: C.TIME_UNSET)
            .setIsSeekable(!now.isLive)
            .setIsDynamic(now.isLive)
            .build()
        return builder
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(if (now.state == PlaybackState.BUFFERING) Player.STATE_BUFFERING else Player.STATE_READY)
            .setPlayWhenReady(now.state != PlaybackState.PAUSED, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs(now.positionMs)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val playing = snapshot.nowPlaying?.state != PlaybackState.PAUSED
        if (playWhenReady != playing) session.control(PlayerCommand.TogglePause)
        return Futures.immediateVoidFuture()
    }

    /** Back/forward buttons arrive here with the target position already computed (±10 s). */
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        session.control(PlayerCommand.SeekTo(positionMs.coerceAtLeast(0)))
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        session.control(PlayerCommand.Stop)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
