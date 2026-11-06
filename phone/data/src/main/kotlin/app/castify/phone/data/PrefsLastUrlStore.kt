package app.castify.phone.data

import android.content.Context

/**
 * Legacy (v0–v0.3): the last visited page, from before tabs existed. Only read once to
 * migrate it into the first tab, then cleared.
 */
class PrefsLastUrlStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("castify", Context.MODE_PRIVATE)

    fun get(): String? = prefs.getString(KEY, null)
    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object { const val KEY = "last_url" }
}
