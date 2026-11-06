package app.castify.domain.update

/**
 * App version `major.minor.patch` (tags look like `v0.5.0`). One number for both apps: a
 * GitHub release always ships the phone and the TV APK together.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    /** `0.5.0`. */
    override fun toString() = "$major.$minor.$patch"

    /** What the user reads: `v0.5.0`. */
    val label: String get() = "v$this"

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d{1,3})\.(\d{1,2})\.(\d{1,2})$""")

        /** `0.5.0` or `v0.5.0`; anything else (suffixes, missing parts, `latest`) is null. */
        fun parse(text: String?): AppVersion? {
            val match = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
            val (major, minor, patch) = match.destructured
            return AppVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

/** Which app runs: picks its APK in the release. */
enum class AppKind(val assetName: String) {
    PHONE("castify-phone.apk"),
    TV("castify-tv.apk"),
}

/** A published release as the source sees it (GitHub: tag + attached files). */
data class Release(val tag: String, val assets: List<ReleaseAsset>)

data class ReleaseAsset(val name: String, val url: String, val sizeBytes: Long)

/** A newer version of this app, ready to download. */
data class AppUpdate(val version: AppVersion, val apkUrl: String, val sizeBytes: Long) {

    companion object {
        /**
         * The update for [kind] if [release] is strictly newer than [current] and carries its
         * APK. Same or older version, unreadable tag or missing APK → null (nothing to show).
         */
        fun from(release: Release, current: AppVersion, kind: AppKind): AppUpdate? {
            val version = AppVersion.parse(release.tag) ?: return null
            if (version <= current) return null
            val asset = release.assets.firstOrNull { it.name == kind.assetName } ?: return null
            return AppUpdate(version, asset.url, asset.sizeBytes)
        }
    }
}
