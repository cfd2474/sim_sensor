package com.taksolutions.simsensor.update

import com.taksolutions.simsensor.connection.LinkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Chunk 4.17: the app prompts when its server offers a newer app, and says how to get it. */
class AppUpdateTest {
    private val offered = OfferedApp("0.32.0", 3200, "https://sim.example.org/download/simsensor.apk")
    private fun check(installed: Int, source: InstallSource = InstallSource.SIDELOAD, kiosk: Boolean = false, app: OfferedApp? = offered) =
        AppVersionCheck.update(installed, "0.29.0", app, source, kiosk, "com.taksolutions.simsensor")

    @Test
    fun onlyAnOlderAppIsPrompted() {
        assertEquals("0.32.0", check(2900)!!.offered.version)
        assertNull("same version", check(3200))
        assertNull("phone newer than the server's app", check(3300))
        assertNull("nothing published", check(2900, app = null))
    }

    @Test
    fun theButtonFitsHowTheAppWasInstalled() {
        assertEquals(UpdateAction.OpenDownload(offered.downloadUrl), check(2900, InstallSource.SIDELOAD)!!.action)
        assertEquals(UpdateAction.AskAdministrator, check(2900, InstallSource.MANAGED)!!.action)
        assertEquals(
            "a Play copy may only be sent to Play",
            UpdateAction.OpenPlay("https://play.google.com/store/apps/details?id=com.taksolutions.simsensor"),
            check(2900, InstallSource.PLAY)!!.action,
        )
        assertEquals("kiosk mode can't open other apps", UpdateAction.AskAdministrator, check(2900, InstallSource.SIDELOAD, kiosk = true)!!.action)
    }

    @Test
    fun installersAreRecognised() {
        assertEquals(InstallSource.PLAY, InstallSource.of("com.android.vending"))
        assertEquals(InstallSource.SIDELOAD, InstallSource.of(null))
        assertEquals(InstallSource.SIDELOAD, InstallSource.of("com.google.android.packageinstaller"))
        assertEquals(InstallSource.SIDELOAD, InstallSource.of("com.android.shell"))
        assertEquals(InstallSource.MANAGED, InstallSource.of("com.example.mdm.agent"))
    }

    @Test
    fun theServersAnswerIsReadStrictly() {
        val body = """{"available":true,"url":"https://s/download/simsensor.apk","app":{"version":"0.32.0","versionCode":3200,"sha256":"x","sizeBytes":1,"publishedAt":"t"}}"""
        assertEquals(OfferedApp("0.32.0", 3200, "https://s/download/simsensor.apk"), HttpOfferedAppSource.parse(body))
        assertNull(HttpOfferedAppSource.parse("""{"available":false,"url":"https://s/download/simsensor.apk"}"""))
        assertNull(HttpOfferedAppSource.parse("""{"available":true,"url":"u","app":{"version":"0.32.0"}}"""))
    }

    @Test
    fun theCheckerAsksOnConnectAndLaterHidesThatVersion() = runBlocking {
        var answer: OfferedApp? = offered
        val checker = AppUpdateChecker(source = { answer }, decide = { check(2900, app = it) })
        val link = MutableStateFlow<LinkStatus>(LinkStatus.Connecting)
        val scope = CoroutineScope(coroutineContext + Job())
        checker.start(scope, serverUrl = { "https://sim.example.org" }, link = link)
        delay(50)
        assertNull("not connected yet", checker.update.value)
        link.value = LinkStatus.Connected("0.32.0")
        delay(50)
        assertEquals(3200, checker.update.value!!.offered.versionCode)
        answer = null // a failed check keeps the last answer
        checker.later()
        delay(50)
        assertNull("Later hides 0.32.0", checker.update.value)
        scope.cancel()
    }
}
