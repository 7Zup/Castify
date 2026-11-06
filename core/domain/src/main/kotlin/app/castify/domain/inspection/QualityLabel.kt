package app.castify.domain.inspection

import app.castify.domain.model.Quality

/**
 * Names qualities the way people know them (1080p, 720p…).
 *
 * Widescreen movies are encoded without black bars, so their real height is unusual
 * (1920x800, 1280x532…). The tier is therefore taken from the **width** when known; the
 * height is only used as a fallback.
 */
object QualityLabel {

    private val tiersByWidth = listOf(
        3840 to "2160p", 2560 to "1440p", 1920 to "1080p", 1280 to "720p",
        854 to "480p", 640 to "360p", 426 to "240p",
    )
    private val tiersByHeight = listOf(
        2160 to "2160p", 1440 to "1440p", 1080 to "1080p", 720 to "720p",
        480 to "480p", 360 to "360p", 240 to "240p",
    )

    /** Width tiers tolerate ~5% under the nominal size (e.g. 1904 wide is still 1080p). */
    fun of(width: Int?, height: Int): String {
        if (width != null && width > 0) {
            tiersByWidth.firstOrNull { (min, _) -> width >= min * 0.95 }?.let { return it.second }
        } else {
            tiersByHeight.firstOrNull { (min, _) -> height >= min * 0.95 }?.let { return it.second }
        }
        return "${height}p"
    }

    /** Best first; when two sizes share a label, the larger one is kept. */
    fun qualities(sizes: List<Pair<Int?, Int>>): List<Quality> =
        sizes.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Int?, Int>> { it.first ?: 0 }.thenByDescending { it.second })
            .map { (w, h) -> Quality(height = h, label = of(w, h)) }
            .distinctBy { it.label }
}
