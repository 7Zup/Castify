package app.castify.domain.usecase

import app.castify.domain.model.CastDevice
import app.castify.domain.model.DeviceKind
import app.castify.domain.model.MediaStream
import app.castify.domain.model.NearbyTv
import app.castify.domain.model.TvDevice
import app.castify.domain.playback.BufferPolicy
import app.castify.domain.playback.PlayerCommand
import app.castify.domain.port.CastDeviceScanner
import app.castify.domain.port.Player
import app.castify.domain.port.TvDiscovery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

// ---- Phone side ----

/**
 * TVs found by every discovery method at once (UDP broadcast + mDNS): one entry per TV
 * name, the first method listed wins (its address is the most reliable), sorted by name.
 */
class ObserveTvDevices(private vararg val discoveries: TvDiscovery) {
    operator fun invoke(): Flow<List<TvDevice>> =
        combine(discoveries.map { it.devices().onStart { emit(emptyList()) } }) { lists -> merge(lists.toList()) }
            .distinctUntilChanged()

    companion object {
        fun merge(lists: List<List<TvDevice>>): List<TvDevice> =
            lists.flatten().distinctBy { it.name }.sortedBy { it.name.lowercase() }
    }
}

/**
 * The "Play on" list: Castify TVs (ready) plus every other Google Cast device on the Wi-Fi
 * (app not launched). A Cast device running Castify is listed once, as ready. A failing or
 * silent Cast scan never hides the Castify TVs.
 */
class ObserveNearbyTvs(
    private val castifyTvs: ObserveTvDevices,
    private val castScanner: CastDeviceScanner,
) {
    operator fun invoke(): Flow<List<NearbyTv>> =
        combine(
            castifyTvs(),
            castScanner.devices().onStart { emit(emptyList()) }.catch { emit(emptyList()) },
        ) { castify, cast -> merge(castify, cast) }
            .distinctUntilChanged()

    companion object {
        /**
         * Matching by address, or by name when the addresses differ (IPv4 on one side, IPv6 on
         * the other): the TV app advertises the device name the Cast service also uses.
         * Order: ready first, then the others, each by name. Speaker groups are hidden.
         */
        fun merge(castify: List<TvDevice>, cast: List<CastDevice>): List<NearbyTv> {
            val devices = cast.filterNot { it.isGroup }
            fun matchOf(tv: TvDevice) =
                devices.firstOrNull { it.host == tv.host } ?: devices.firstOrNull { it.name.equals(tv.name, ignoreCase = true) }

            val matched = mutableSetOf<CastDevice>()
            val ready = castify.map { tv ->
                val match = matchOf(tv)?.also { matched += it }
                NearbyTv(tv.name, match.kind(), tv, tv.host)
            }
            val notLaunched = devices
                .filterNot { it in matched }
                .distinctBy { it.host }
                .map { NearbyTv(it.name, it.kind(), castify = null, host = it.host) }
            return ready.sortedBy { it.name.lowercase() } + notLaunched.sortedBy { it.name.lowercase() }
        }

        private fun CastDevice?.kind() = if (this == null || hasVideo) DeviceKind.TV else DeviceKind.SPEAKER
    }
}

/**
 * Turns what the user typed in the address bar into a URL to load. The history records the
 * page when it loads ([BrowsingHistory.visit]), like any other visited page.
 */
class OpenPage {
    operator fun invoke(input: String): String? = normalize(input)

    companion object {
        private const val SEARCH_PREFIX = "https://duckduckgo.com/?q="

        fun normalize(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
            val looksLikeHost = !trimmed.contains(' ') && trimmed.contains('.')
            return if (looksLikeHost) "https://$trimmed"
            else SEARCH_PREFIX + java.net.URLEncoder.encode(trimmed, "UTF-8")
        }
    }
}

// ---- TV side ----

class PlayStream(private val player: Player, private val policy: BufferPolicy = BufferPolicy.V0) {
    operator fun invoke(stream: MediaStream, startPositionMs: Long = 0) = player.play(stream, policy, startPositionMs)
}

/**
 * Applies a player command. Returns false for TV-only commands ([PlayerCommand.isTvOnly]):
 * the TV screen handles those itself (diagnostics overlay, subtitle menu).
 */
class HandlePlayerCommand(private val player: Player) {
    operator fun invoke(command: PlayerCommand): Boolean {
        when (command) {
            PlayerCommand.TogglePause -> player.togglePause()
            is PlayerCommand.SeekBy -> player.seekBy(command.deltaMs)
            is PlayerCommand.SeekTo -> player.seekTo(command.positionMs)
            PlayerCommand.Stop -> player.stop()
            is PlayerCommand.SelectSubtitle -> player.selectSubtitle(command.trackId)
            PlayerCommand.ToggleDiagnostics, PlayerCommand.OpenSubtitleMenu, PlayerCommand.ShowControls -> return false
        }
        return true
    }
}
