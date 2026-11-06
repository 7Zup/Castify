package app.castify.domain

import app.castify.domain.update.AcceptedUpdateStore
import app.castify.domain.update.AppKind
import app.castify.domain.update.AppUpdate
import app.castify.domain.update.AppUpdater
import app.castify.domain.update.AppUpdater.State
import app.castify.domain.update.AppVersion
import app.castify.domain.update.InstallOutcome
import app.castify.domain.update.Release
import app.castify.domain.update.ReleaseAsset
import app.castify.domain.update.UpdateInstaller
import app.castify.domain.update.UpdateSource
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {

    private val v040 = AppVersion(0, 4, 0)

    private fun release(tag: String) = Release(
        tag,
        listOf(
            ReleaseAsset("castify-phone.apk", "https://dl/phone.apk", 22_000_000),
            ReleaseAsset("castify-tv.apk", "https://dl/tv.apk", 85_000_000),
        ),
    )

    // ---------- versions ----------

    @Test
    fun `parses plain and v-prefixed versions`() {
        assertEquals(AppVersion(0, 5, 0), AppVersion.parse("0.5.0"))
        assertEquals(AppVersion(1, 12, 3), AppVersion.parse("v1.12.3"))
        assertEquals(AppVersion(0, 5, 0), AppVersion.parse(" V0.5.0 "))
        assertEquals("v0.5.0", AppVersion(0, 5, 0).label)
    }

    @Test
    fun `rejects malformed versions`() {
        listOf(null, "", "latest", "0.5", "0.5.0-beta", "v0.5.0.1", "a.b.c").forEach {
            assertNull(it, AppVersion.parse(it))
        }
    }

    @Test
    fun `compares numerically, not as text`() {
        assertTrue(AppVersion(0, 10, 0) > AppVersion(0, 9, 9))
        assertTrue(AppVersion(1, 0, 0) > AppVersion(0, 99, 99))
        assertTrue(AppVersion(0, 5, 1) > AppVersion(0, 5, 0))
        assertEquals(0, AppVersion(0, 5, 0).compareTo(AppVersion(0, 5, 0)))
    }

    // ---------- picking the update ----------

    @Test
    fun `newer release gives the apk of this app`() {
        assertEquals(AppUpdate(AppVersion(0, 5, 0), "https://dl/phone.apk", 22_000_000), AppUpdate.from(release("v0.5.0"), v040, AppKind.PHONE))
        assertEquals("https://dl/tv.apk", AppUpdate.from(release("v0.5.0"), v040, AppKind.TV)?.apkUrl)
    }

    @Test
    fun `same, older, unreadable or apk-less release gives nothing`() {
        assertNull(AppUpdate.from(release("v0.4.0"), v040, AppKind.PHONE))
        assertNull(AppUpdate.from(release("v0.3.9"), v040, AppKind.PHONE))
        assertNull(AppUpdate.from(release("nightly"), v040, AppKind.PHONE))
        assertNull(AppUpdate.from(Release("v0.5.0", listOf(ReleaseAsset("castify-tv.apk", "u", 1))), v040, AppKind.PHONE))
    }

    // ---------- updater ----------

    private class FakeSource(var release: Release?, var fails: Boolean = false) : UpdateSource {
        var calls = 0
        override suspend fun latest(): Release? {
            calls++
            if (fails) throw java.io.IOException("offline")
            return release
        }
    }

    private class FakeStore : AcceptedUpdateStore {
        override var accepted: AppVersion? = null
    }

    private class FakeInstaller : UpdateInstaller {
        var allowed = true
        var hasSettingsScreen = true
        var permissionRequests = 0
        override fun canInstall() = allowed
        override fun requestInstallPermission(): Boolean {
            permissionRequests++
            return hasSettingsScreen
        }
        var downloadFails = false
        var hangDownload = false
        var outcome = InstallOutcome.CANCELLED
        var installs = 0
        override suspend fun download(update: AppUpdate, onProgress: (Long, Long) -> Unit) {
            onProgress(update.sizeBytes / 2, update.sizeBytes)
            if (hangDownload) awaitCancellation()
            if (downloadFails) throw java.io.IOException("wifi dropped")
            onProgress(update.sizeBytes, update.sizeBytes)
        }
        override suspend fun install(update: AppUpdate): InstallOutcome {
            installs++
            return outcome
        }
    }

    @Test
    fun `check shows a newer release`() = runTest {
        val updater = AppUpdater(v040, AppKind.PHONE, FakeSource(release("v0.5.0")), FakeInstaller(), FakeStore(), backgroundScope)
        updater.check()
        runCurrent()
        val s = updater.state.value as State.Available
        assertEquals(AppVersion(0, 5, 0), s.update.version)
    }

    @Test
    fun `check stays silent when up to date, no release, or offline`() = runTest {
        listOf(FakeSource(release("v0.4.0")), FakeSource(null), FakeSource(release("v0.5.0"), fails = true)).forEach { source ->
            val updater = AppUpdater(v040, AppKind.PHONE, source, FakeInstaller(), FakeStore(), backgroundScope)
            updater.check()
            runCurrent()
            assertEquals(State.Idle, updater.state.value)
        }
    }

    @Test
    fun `not now hides it until the next check`() = runTest {
        val source = FakeSource(release("v0.5.0"))
        val updater = AppUpdater(v040, AppKind.PHONE, source, FakeInstaller(), FakeStore(), backgroundScope)
        updater.check(); runCurrent()
        updater.dismiss()
        assertEquals(State.Idle, updater.state.value)
        updater.check(); runCurrent() // next launch
        assertTrue(updater.state.value is State.Available)
        assertEquals(2, source.calls)
    }

    @Test
    fun `update downloads then hands over to android`() = runTest {
        val installer = FakeInstaller()
        val updater = AppUpdater(v040, AppKind.TV, FakeSource(release("v0.5.0")), installer, FakeStore(), backgroundScope)
        updater.check(); runCurrent()
        updater.accept(); runCurrent()
        assertEquals(1, installer.installs)
        // The user cancelled Android's confirmation: nothing until the next launch.
        assertEquals(State.Idle, updater.state.value)
    }

    @Test
    fun `progress is reported while downloading`() = runTest {
        val installer = FakeInstaller().apply { hangDownload = true }
        val updater = AppUpdater(v040, AppKind.PHONE, FakeSource(release("v0.5.0")), installer, FakeStore(), backgroundScope)
        updater.check(); runCurrent()
        updater.accept(); runCurrent()
        val s = updater.state.value as State.Downloading
        assertEquals(11_000_000L, s.downloadedBytes)
        assertEquals(22_000_000L, s.totalBytes)
    }

    @Test
    fun `cancel stops the download`() = runTest {
        val installer = FakeInstaller().apply { hangDownload = true }
        val updater = AppUpdater(v040, AppKind.PHONE, FakeSource(release("v0.5.0")), installer, FakeStore(), backgroundScope)
        updater.check(); runCurrent()
        updater.accept(); runCurrent()
        updater.dismiss(); runCurrent()
        assertEquals(State.Idle, updater.state.value)
        assertEquals(0, installer.installs)
    }

    @Test
    fun `failed download offers retry, which downloads again`() = runTest {
        val installer = FakeInstaller().apply { downloadFails = true }
        val updater = AppUpdater(v040, AppKind.PHONE, FakeSource(release("v0.5.0")), installer, FakeStore(), backgroundScope)
        updater.check(); runCurrent()
        updater.accept(); runCurrent()
        assertTrue(updater.state.value is State.Failed)
        installer.downloadFails = false
        updater.accept(); runCurrent()
        assertEquals(1, installer.installs)
    }

    @Test
    fun `failed install shows the failure`() = runTest {
        val installer = FakeInstaller().apply { outcome = InstallOutcome.FAILED }
        val updater = AppUpdater(v040, AppKind.PHONE, FakeSource(release("v0.5.0")), installer, FakeStore(), backgroundScope)
        updater.check(); runCurrent()
        updater.accept(); runCurrent()
        assertTrue(updater.state.value is State.Failed)
    }

    @Test
    fun `dismiss is ignored once android is installing`() = runTest {
        val installer = object : UpdateInstaller {
            override fun canInstall() = true
            override fun requestInstallPermission() = true
            override suspend fun download(update: AppUpdate, onProgress: (Long, Long) -> Unit) = Unit
            override suspend fun install(update: AppUpdate): InstallOutcome = awaitCancellation()
        }
        val updater = AppUpdater(v040, AppKind.TV, FakeSource(release("v0.5.0")), installer, FakeStore(), backgroundScope)
        updater.check(); runCurrent()
        updater.accept(); runCurrent()
        updater.dismiss()
        assertTrue(updater.state.value is State.Installing)
    }

    @Test
    fun `a new launch while android's confirmation was left open checks again`() = runTest {
        val installer = object : UpdateInstaller {
            override fun canInstall() = true
            override fun requestInstallPermission() = true
            override suspend fun download(update: AppUpdate, onProgress: (Long, Long) -> Unit) = Unit
            override suspend fun install(update: AppUpdate): InstallOutcome = awaitCancellation()
        }
        val updater = AppUpdater(v040, AppKind.PHONE, FakeSource(release("v0.5.0")), installer, FakeStore(), backgroundScope)
        updater.check(); runCurrent()
        updater.accept(); runCurrent()
        updater.check(); runCurrent()
        assertTrue(updater.state.value is State.Available)
    }

    // ---------- "Install unknown apps" first ----------

    @Test
    fun `update asks for the permission first, then downloads on return`() = runTest {
        val installer = FakeInstaller().apply { allowed = false }
        val updater = AppUpdater(v040, AppKind.PHONE, FakeSource(release("v0.5.0")), installer, FakeStore(), backgroundScope)
        updater.check(); runCurrent()
        updater.accept(); runCurrent()
        assertEquals(1, installer.permissionRequests)
        assertTrue(updater.state.value is State.WaitingPermission)
        installer.allowed = true
        updater.onResume(); runCurrent()
        assertEquals(1, installer.installs)
    }

    @Test
    fun `back without the permission shows the prompt again`() = runTest {
        val installer = FakeInstaller().apply { allowed = false }
        val updater = AppUpdater(v040, AppKind.PHONE, FakeSource(release("v0.5.0")), installer, FakeStore(), backgroundScope)
        updater.check(); runCurrent()
        updater.accept(); runCurrent()
        updater.onResume(); runCurrent()
        assertTrue(updater.state.value is State.Available)
        assertEquals(0, installer.installs)
    }

    @Test
    fun `android restarting the app after the permission resumes the update by itself`() = runTest {
        val store = FakeStore()
        val installer = FakeInstaller().apply { allowed = false }
        AppUpdater(v040, AppKind.PHONE, FakeSource(release("v0.5.0")), installer, store, backgroundScope).apply {
            check(); runCurrent()
            accept(); runCurrent()
        }
        // Android kills the app when the switch goes on; the next launch is a new updater.
        installer.allowed = true
        val relaunched = AppUpdater(v040, AppKind.PHONE, FakeSource(release("v0.5.0")), installer, store, backgroundScope)
        relaunched.check(); runCurrent()
        assertEquals(1, installer.installs)
    }

    @Test
    fun `not now forgets the accepted version`() = runTest {
        val store = FakeStore()
        val installer = FakeInstaller().apply { allowed = false }
        val updater = AppUpdater(v040, AppKind.PHONE, FakeSource(release("v0.5.0")), installer, store, backgroundScope)
        updater.check(); runCurrent()
        updater.accept(); runCurrent()
        updater.onResume(); runCurrent() // came back without switching it on
        updater.dismiss()
        assertNull(store.accepted)
    }

    @Test
    fun `no settings screen goes straight to the download`() = runTest {
        val installer = FakeInstaller().apply { allowed = false; hasSettingsScreen = false }
        val updater = AppUpdater(v040, AppKind.TV, FakeSource(release("v0.5.0")), installer, FakeStore(), backgroundScope)
        updater.check(); runCurrent()
        updater.accept(); runCurrent()
        assertEquals(1, installer.installs)
    }

    @Test
    fun `check is ignored while an update is shown`() = runTest {
        val source = FakeSource(release("v0.5.0"))
        val updater = AppUpdater(v040, AppKind.PHONE, source, FakeInstaller().apply { hangDownload = true }, FakeStore(), backgroundScope)
        updater.check(); runCurrent()
        updater.accept(); runCurrent()
        updater.check(); runCurrent()
        assertEquals(1, source.calls)
        assertTrue(updater.state.value is State.Downloading)
    }
}
