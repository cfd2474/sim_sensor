package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.server.routes.UpdateRequest
import com.taksolutions.simsensor.server.support.TEST_CONFIG
import com.taksolutions.simsensor.server.support.adminBrowser
import com.taksolutions.simsensor.server.support.instructorBrowser
import com.taksolutions.simsensor.server.support.postJson
import com.taksolutions.simsensor.server.support.withApp
import com.taksolutions.simsensor.server.system.ReleaseSource
import com.taksolutions.simsensor.server.system.UpdateInfo
import com.taksolutions.simsensor.server.system.UpdateService
import com.taksolutions.simsensor.server.system.Version
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Chunk 4.6: Settings → Server update hands requests to the updater through a shared folder. */
class ServerUpdateTest {
    private val notes = """
        # Release notes

        ## v0.26.0 — 2026-10-17

        - Server updates from the console.

        ## v0.25.0 — 2026-10-16

        - Create-exercise dialog.
    """.trimIndent()

    private class FakeReleases(private val tags: List<String>, private val notes: String) : ReleaseSource {
        override suspend fun tags() = tags
        override suspend fun releaseNotes(tag: String) = notes
    }

    @Test
    fun anAdminHandsAnUpdateToTheUpdater() {
        val dir = Files.createTempDirectory("update").toFile()
        val config = TEST_CONFIG.copy(version = "0.25.0", updateDir = dir)
        withApp(config = config, releases = FakeReleases(listOf("v0.24.0", "v0.26.0", "v0.25.0", "not-a-version"), notes)) {
            val admin = adminBrowser()
            var info = admin.get("/api/system/update").body<UpdateInfo>()
            assertEquals("0.26.0", info.latestVersion)
            assertTrue(info.updateAvailable)
            assertTrue(info.releaseNotes!!.contains("Server updates from the console") && !info.releaseNotes!!.contains("Create-exercise"),
                "only the notes of versions after the running one")
            assertFalse(info.updaterReady, "no updater yet")
            assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/system/update", UpdateRequest("v0.26.0")).status)

            dir.resolve("heartbeat").writeText((System.currentTimeMillis() / 1000).toString()) // the updater is alive
            assertEquals(HttpStatusCode.UnprocessableEntity, admin.postJson("/api/system/update", UpdateRequest("v0.24.0")).status, "older")
            assertEquals(HttpStatusCode.UnprocessableEntity, admin.postJson("/api/system/update", UpdateRequest("v0.27.0")).status, "not on GitHub")
            assertEquals(HttpStatusCode.UnprocessableEntity, admin.postJson("/api/system/update", UpdateRequest("main; rm -rf /")).status)
            val (instructor, _) = instructorBrowser(admin, "i@example.org")
            assertEquals(HttpStatusCode.Forbidden, instructor.postJson("/api/system/update", UpdateRequest("v0.26.0")).status)

            val accepted = admin.postJson("/api/system/update", UpdateRequest("0.26.0"))
            assertEquals(HttpStatusCode.Accepted, accepted.status)
            assertTrue(accepted.body<UpdateInfo>().pending)
            assertTrue(dir.resolve("request.json").readText().contains("\"tag\":\"v0.26.0\""))
            assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/system/update", UpdateRequest("v0.26.0")).status, "one at a time")

            // The updater picks it up and reports progress.
            dir.resolve("request.json").delete()
            dir.resolve("status.json").writeText("""{"state":"running","step":"build","message":"Building","tag":"v0.26.0"}""")
            info = admin.get("/api/system/update").body<UpdateInfo>()
            assertEquals("build", info.status?.step)
            assertTrue(info.pending)
        }
    }

    @Test
    fun anInstallWithoutTheUpdaterSaysSo() = withApp(releases = FakeReleases(emptyList(), "")) {
        val info = adminBrowser().get("/api/system/update").body<UpdateInfo>()
        assertFalse(info.updaterReady)
        assertTrue(info.updaterMessage!!.contains("aren't set up"))
    }

    @Test
    fun versionsAndNotesAreReadStrictly() {
        assertEquals(Version(0, 26, 0), Version.parse("v0.26.0"))
        assertEquals(null, Version.parse("v0.26.0-rc1"))
        assertTrue(Version(0, 10, 0) > Version(0, 9, 9))
        assertEquals(null, UpdateService.sectionsAfter(notes, Version(0, 26, 0)))
    }
}
