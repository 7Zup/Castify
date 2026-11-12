package app.castify.tv.data

import android.content.Context
import app.castify.domain.port.TvSettings

/** TV settings in the app's preferences (survive restarts and updates). */
class PrefsTvSettings(context: Context) : TvSettings {
    private val prefs = context.applicationContext.getSharedPreferences("tv-settings", Context.MODE_PRIVATE)

    override var autoNext: Boolean
        get() = prefs.getBoolean(KEY_AUTO_NEXT, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_NEXT, value).apply() }

    private companion object {
        const val KEY_AUTO_NEXT = "autoNext"
    }
}
