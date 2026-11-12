package app.castify.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.net.Uri
import android.provider.Settings
import app.castify.domain.update.AcceptedUpdateStore
import app.castify.domain.update.AppVersion
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import app.castify.domain.update.AppUpdate
import app.castify.domain.update.InstallOutcome
import app.castify.domain.update.UpdateInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads the APK into the app cache, then installs it with [PackageInstaller]. Android
 * shows its own confirmation; the first time it also asks to allow "Install unknown apps"
 * for Castify and comes back to the install by itself.
 */
class ApkInstaller(private val context: Context) : UpdateInstaller {

    private val apk get() = File(context.cacheDir, "update/castify.apk")

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun requestInstallPermission(): Boolean {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Some Android TV builds have no such screen: the install then asks by itself.
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Removes a leftover APK from a previous update (called at launch). */
    fun cleanUp() {
        apk.delete()
    }

    override suspend fun download(update: AppUpdate, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val target = apk.apply { parentFile?.mkdirs(); delete() }
        val partial = File(target.path + ".part").apply { delete() }
        // GitHub redirects (https → https) to its file storage; HttpURLConnection follows it.
        val connection = URL(update.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Castify")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Download answered ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes
            var done = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive() // Cancel
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done - lastReport >= REPORT_EVERY_BYTES) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            if (update.sizeBytes > 0 && done != update.sizeBytes) throw IOException("Incomplete download: $done / ${update.sizeBytes}")
            onProgress(done, total)
            if (!partial.renameTo(target)) throw IOException("Can't keep the downloaded file")
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    override suspend fun install(update: AppUpdate): InstallOutcome {
        val file = apk
        if (!file.exists()) return InstallOutcome.FAILED
        val installer = context.packageManager.packageInstaller
        val sessionId = withContext(Dispatchers.IO) {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Silent from the 2nd update on Android 12+ (Castify is then its own installer).
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("castify.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            }
            id
        }
        val result = CompletableDeferred<InstallOutcome>()
        pending[sessionId] = result
        val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val callback = PendingIntent.getBroadcast(context, sessionId, intent, flags)
        installer.openSession(sessionId).use { it.commit(callback.intentSender) }
        return try {
            result.await()
        } finally {
            pending.remove(sessionId)
        }
    }

    internal companion object {
        const val TIMEOUT_MS = 15_000
        const val REPORT_EVERY_BYTES = 256 * 1024L
        /** Install sessions waiting for Android's answer (the process lives while it asks). */
        val pending = ConcurrentHashMap<Int, CompletableDeferred<InstallOutcome>>()
    }
}

/** Android's answers to an install session: confirmation needed, done, cancelled, failed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val outcome = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                }
                InstallOutcome.FAILED
            }
            PackageInstaller.STATUS_SUCCESS -> InstallOutcome.INSTALLED
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.CANCELLED
            else -> {
                Log.w("Castify", "Update install failed: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                InstallOutcome.FAILED
            }
        }
        ApkInstaller.pending[sessionId]?.complete(outcome)
    }
}

/** The version the user said `Update` to, in the app's preferences (survives a process death). */
class PrefsAcceptedUpdateStore(context: Context) : AcceptedUpdateStore {
    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)

    override var accepted: AppVersion?
        get() = AppVersion.parse(prefs.getString(KEY, null))
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, value.toString()) }.apply()
        }

    private companion object {
        const val KEY = "accepted_version"
    }
}

/** The last version whose "What's new" was seen, in the app's preferences. */
class PrefsSeenVersionStore(context: Context) : app.castify.domain.update.SeenVersionStore {
    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)

    override var lastSeen: AppVersion?
        get() = AppVersion.parse(prefs.getString(KEY, null))
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, value.toString()) }.apply()
        }

    private companion object {
        const val KEY = "whats_new_seen"
    }
}

/** True when the installed app replaced an older one (an update), false for a first install. */
fun wasUpdated(context: Context): Boolean {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return info.lastUpdateTime > info.firstInstallTime
}
