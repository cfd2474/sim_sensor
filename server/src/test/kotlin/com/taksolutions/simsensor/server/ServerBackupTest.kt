package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.server.routes.RestoreRequest
import com.taksolutions.simsensor.server.routes.RetentionRequest
import com.taksolutions.simsensor.server.routes.UpdateRequest
import com.taksolutions.simsensor.server.support.TEST_CONFIG
import com.taksolutions.simsensor.server.support.adminBrowser
import com.taksolutions.simsensor.server.support.instructorBrowser
import com.taksolutions.simsensor.server.support.postJson
import com.taksolutions.simsensor.server.support.putJson
import com.taksolutions.simsensor.server.support.withApp
import com.taksolutions.simsensor.server.system.BackupsInfo
import com.taksolutions.simsensor.server.system.ReleaseSource
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Chunk 4.9: backups, restores and their retention are handed to the updater like updates. */
class ServerBackupTest {
    private object NoReleases : ReleaseSource {
        override suspend fun tags() = listOf("v0.29.0")
        override suspend fun releaseNotes(tag: String): String? = null
    }

    private val listed = """{"backups":[
        {"name":"manual-20261017T052211Z.dump","sizeBytes":40960,"createdAt":"2026-10-17T05:22:11Z"},
        {"name":"pre-v0.26.2-20261017T044051Z.dump","sizeBytes":38912,"createdAt":"2026-10-17T04:40:51Z"},
        {"name":"pre-restore-20261016T010000Z.dump","sizeBytes":1024,"createdAt":"2026-10-16T01:00:00Z"}
    ],"retentionDays":7,"inventoryAt":"2026-10-17T05:22:12Z"}"""

    @Test
    fun anAdminBacksUpRestoresAndSetsRetention() {
        val dir = Files.createTempDirectory("update").toFile()
        withApp(config = TEST_CONFIG.copy(version = "0.28.0", updateDir = dir), releases = NoReleases) {
            val admin = adminBrowser()
            var info = admin.get("/api/system/backups").body<BackupsInfo>()
            assertEquals(7, info.retentionDays, "7 days by default")
            assertTrue(info.backups.isEmpty())
            assertTrue(dir.resolve("request.json").readText().contains("\"action\":\"inventory\""), "asked for at start and when there's no list")
            assertEquals(HttpStatusCode.Conflict, admin.post("/api/system/backups").status, "no updater yet")

            // The updater is alive and has listed the backups.
            dir.resolve("heartbeat").writeText((System.currentTimeMillis() / 1000).toString())
            dir.resolve("backups.json").writeText(listed)
            info = admin.get("/api/system/backups").body<BackupsInfo>()
            assertEquals(listOf("manual", "before-update", "before-restore"), info.backups.map { it.kind })
            assertFalse(info.busy, "a waiting inventory is not work under way")

            val (instructor, _) = instructorBrowser(admin, "i@example.org")
            assertEquals(HttpStatusCode.Forbidden, instructor.get("/api/system/backups").status)
            assertEquals(HttpStatusCode.Forbidden, instructor.post("/api/system/backups").status)

            // Retention: checked, written for the updater, applied now.
            assertEquals(HttpStatusCode.UnprocessableEntity, admin.putJson("/api/system/backups/retention", RetentionRequest(0)).status)
            assertEquals(HttpStatusCode.UnprocessableEntity, admin.putJson("/api/system/backups/retention", RetentionRequest(366)).status)
            val saved = admin.putJson("/api/system/backups/retention", RetentionRequest(14))
            assertEquals(HttpStatusCode.OK, saved.status)
            assertEquals(14, saved.body<BackupsInfo>().retentionDays)
            assertEquals("""{"days":14}""", dir.resolve("retention.json").readText())

            // A backup replaces the waiting inventory request; then one thing at a time.
            val backup = admin.post("/api/system/backups")
            assertEquals(HttpStatusCode.Accepted, backup.status)
            assertTrue(backup.body<BackupsInfo>().busy)
            assertTrue(dir.resolve("request.json").readText().contains("\"action\":\"backup\""))
            assertEquals(HttpStatusCode.Conflict, admin.post("/api/system/backups").status)
            assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/system/update", UpdateRequest("v0.29.0")).status)
            dir.resolve("request.json").delete()

            // Restores: only a listed file, never a path.
            assertEquals(HttpStatusCode.UnprocessableEntity, admin.postJson("/api/system/backups/restore", RestoreRequest("../../etc/passwd")).status)
            assertEquals(HttpStatusCode.UnprocessableEntity, admin.postJson("/api/system/backups/restore", RestoreRequest("other.dump")).status)
            assertEquals(HttpStatusCode.Forbidden, instructor.postJson("/api/system/backups/restore", RestoreRequest("manual-20261017T052211Z.dump")).status)
            assertEquals(HttpStatusCode.Accepted, admin.postJson("/api/system/backups/restore", RestoreRequest("manual-20261017T052211Z.dump")).status)
            val request = dir.resolve("request.json").readText()
            assertTrue(request.contains("\"action\":\"restore\"") && request.contains("\"file\":\"manual-20261017T052211Z.dump\""))
        }
    }

    @Test
    fun aStartingServerAsksForAnInventory() {
        // After an update the new release's scripts run it, refreshing the phone-app download too.
        val dir = Files.createTempDirectory("update").toFile()
        withApp(config = TEST_CONFIG.copy(version = "0.28.0", updateDir = dir), releases = NoReleases) {
            client.get("/health") // the test harness starts the application on its first request
            val request = dir.resolve("request.json")
            val deadline = System.currentTimeMillis() + 5000
            while (!request.exists() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(request.readText().contains("\"action\":\"inventory\""))
        }
    }

    @Test
    fun aWaitingInventoryDoesNotBlockAnUpdate() {
        val dir = Files.createTempDirectory("update").toFile()
        withApp(config = TEST_CONFIG.copy(version = "0.28.0", updateDir = dir), releases = NoReleases) {
            dir.resolve("heartbeat").writeText((System.currentTimeMillis() / 1000).toString())
            dir.resolve("request.json").writeText("""{"action":"inventory"}""")
            assertEquals(HttpStatusCode.Accepted, adminBrowser().postJson("/api/system/update", UpdateRequest("v0.29.0")).status)
            assertTrue(dir.resolve("request.json").readText().contains("\"action\":\"update\""))
        }
    }
}
