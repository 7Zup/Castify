package app.castify.domain.status

import app.castify.domain.model.PlaybackError

/**
 * Offline and error states shown to the user (mockup `docs/mockups/offline-errors.html`).
 * Rule: nothing retries by itself; every error screen has a Retry button (user choice).
 */

/** Welcome screen of the TV (dot + label next to the TV name). */
enum class TvPresence {
    /** No network: no phone can find the TV. Red dot. */
    OFFLINE,
    /** On the network, no phone talking to it. White pulsing dot. */
    STANDBY,
    /** A phone talked to the TV in the last [CONNECTED_WINDOW_MS]. Green dot. */
    CONNECTED;

    companion object {
        /** Connected phones poll the TV every 1–3 s: 6 s without a call = nobody connected. */
        const val CONNECTED_WINDOW_MS = 6_000L

        fun of(online: Boolean, lastPhoneCallAtMs: Long?, nowMs: Long): TvPresence = when {
            !online -> OFFLINE
            lastPhoneCallAtMs != null && nowMs - lastPhoneCallAtMs in 0..CONNECTED_WINDOW_MS -> CONNECTED
            else -> STANDBY
        }
    }
}

/** Why the TV stopped a video. */
enum class PlaybackFailure {
    /** The TV lost its connection (network down, timeout): `Connection lost`. */
    CONNECTION_LOST,
    /** The site answered with an error, or the TV can't play the format: `This video can't be played`. */
    REFUSED;

    companion object {
        /** Media3 error code names that mean "the network is gone". */
        private val NETWORK_CODES = setOf(
            "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
            "ERROR_CODE_TIMEOUT",
        )

        fun of(error: PlaybackError?): PlaybackFailure = when {
            error == null -> REFUSED
            error.httpStatus != null -> REFUSED
            error.code in NETWORK_CODES -> CONNECTION_LOST
            else -> REFUSED
        }
    }
}

/** Why a page of the phone browser could not load. */
enum class PageError {
    /** The phone has no internet: `No internet connection`. */
    OFFLINE,
    /** Internet works but the site does not answer: `Can't reach <site>`. */
    UNREACHABLE;

    companion object {
        /**
         * [connectionFailure] = the browser could not reach the server (DNS, connect, timeout).
         * Other failures (bad certificate, unsupported scheme…) keep the browser's own page: null.
         */
        fun of(connectionFailure: Boolean, online: Boolean): PageError? = when {
            !connectionFailure -> null
            !online -> OFFLINE
            else -> UNREACHABLE
        }
    }
}
