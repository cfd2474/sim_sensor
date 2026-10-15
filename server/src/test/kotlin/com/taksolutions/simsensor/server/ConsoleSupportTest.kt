package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.protocol.ExerciseUpdate
import com.taksolutions.simsensor.protocol.OverridesUpdate
import com.taksolutions.simsensor.protocol.ProtocolCodec
import com.taksolutions.simsensor.protocol.Telemetry
import com.taksolutions.simsensor.protocol.TelemetrySample
import com.taksolutions.simsensor.server.api.ConsoleConfigDto
import com.taksolutions.simsensor.server.api.DeviceTargetDto
import com.taksolutions.simsensor.server.api.EventDto
import com.taksolutions.simsensor.server.api.ExerciseDto
import com.taksolutions.simsensor.server.api.SetOverrideRequest
import com.taksolutions.simsensor.server.support.TestDatabase
import com.taksolutions.simsensor.server.support.adminBrowser
import com.taksolutions.simsensor.server.support.asDevice
import com.taksolutions.simsensor.server.support.assign
import com.taksolutions.simsensor.server.support.browser
import com.taksolutions.simsensor.server.support.createExercise
import com.taksolutions.simsensor.server.support.enrollDevice
import com.taksolutions.simsensor.server.support.fiveGasProfile
import com.taksolutions.simsensor.server.support.instructorBrowser
import com.taksolutions.simsensor.server.support.nextDevice
import com.taksolutions.simsensor.server.support.postJson
import com.taksolutions.simsensor.server.support.TEST_CONFIG
import com.taksolutions.simsensor.server.support.withApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConsoleSupportTest {

    @Test
    fun lifecycleFollowsTheAllowedTransitions() = withApp {
        val admin = adminBrowser()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        suspend fun act(action: String) = admin.post("/api/exercises/${exercise.id}/$action")

        assertEquals(HttpStatusCode.Conflict, act("pause").status)
        assertEquals("running", act("start").body<ExerciseDto>().state)
        assertEquals(HttpStatusCode.Conflict, act("start").status)
        assertEquals("paused", act("pause").body<ExerciseDto>().state)
        assertEquals("running", act("resume").body<ExerciseDto>().state)
        assertEquals("ended", act("end").body<ExerciseDto>().state)
        assertEquals("running", act("start").body<ExerciseDto>().state, "an ended exercise can run again")
        assertEquals("draft", act("reset").body<ExerciseDto>().state)
        assertEquals(HttpStatusCode.NotFound, act("explode").status)

        val events = admin.get("/api/exercises/${exercise.id}/events").body<List<EventDto>>()
        assertEquals(
            listOf("START", "PAUSE", "RESUME", "END", "START", "RESET"),
            events.filter { it.type == "exercise.state" }.reversed().map { it.data["action"] },
        )
        assertEquals("Administrator", events.first().actorName)
    }

    @Test
    fun stateChangesReachDevicesAndResetReleasesOverrides() = withApp {
        val admin = adminBrowser()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        val device = enrollDevice(admin, "Entry 1")
        admin.assign(device.deviceId, exercise.id)
        asDevice(device) {
            admin.post("/api/exercises/${exercise.id}/start")
            assertEquals("running", nextDevice<ExerciseUpdate>().exercise?.state)

            admin.postJson("/api/exercises/${exercise.id}/overrides", SetOverrideRequest(DeviceTargetDto(all = true), mode = "FAULT"))
            assertEquals(1, nextDevice<OverridesUpdate>().overrides.size)

            admin.post("/api/exercises/${exercise.id}/reset")
            assertEquals(emptyList(), nextDevice<OverridesUpdate>().overrides)
            assertEquals("draft", nextDevice<ExerciseUpdate>().exercise?.state)
        }
    }

    @Test
    fun telemetryIsTaggedWithTheRunningSession() = withApp { services ->
        val admin = adminBrowser()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        val device = enrollDevice(admin, "Entry 1")
        admin.assign(device.deviceId, exercise.id)
        admin.post("/api/exercises/${exercise.id}/start")
        asDevice(device) {
            send(Frame.Text(ProtocolCodec.encode(Telemetry(listOf(TelemetrySample(ts = System.currentTimeMillis(), readings = mapOf("CO" to 1.0)))))))
            // Wait until the server processed it (the console isn't watching; poll the writer).
            repeat(50) {
                services.telemetryWriter.flush()
                if (services.telemetryRepository.forDevice(UUID.fromString(device.deviceId)).isNotEmpty()) return@repeat
                kotlinx.coroutines.delay(100)
            }
        }
        val sessionIds = TestDatabase.dataSource.connection.use { c ->
            c.createStatement().executeQuery(
                "SELECT t.session_id, s.id FROM telemetry t JOIN exercise_sessions s ON s.exercise_id = t.exercise_id AND s.ended_at IS NULL",
            ).use { rs -> buildList { while (rs.next()) add(rs.getObject(1) to rs.getObject(2)) } }
        }
        assertEquals(1, sessionIds.size)
        assertNotNull(sessionIds.single().first)
        assertEquals(sessionIds.single().second, sessionIds.single().first)
    }

    @Test
    fun assignmentEventsNameTheExercises() = withApp {
        val admin = adminBrowser()
        val profileId = admin.fiveGasProfile().id
        val first = admin.createExercise("Rail yard", profileId)
        val second = admin.createExercise("Tunnel", profileId)
        val device = enrollDevice(admin, "Entry 1")
        admin.assign(device.deviceId, first.id)
        admin.assign(device.deviceId, second.id)

        val moved = admin.get("/api/exercises/${second.id}/events").body<List<EventDto>>().first { it.type == "device.assigned" }
        assertEquals("Rail yard", moved.data["from"])
        assertEquals("Tunnel", moved.data["to"])
        val joined = admin.get("/api/exercises/${first.id}/events").body<List<EventDto>>().last { it.type == "device.assigned" }
        assertEquals("standby", joined.data["from"])
    }

    @Test
    fun eventsAreOnlyVisibleToTheExercisesInstructors() = withApp {
        val admin = adminBrowser()
        val (bob, _) = instructorBrowser(admin, "bob@example.org")
        val exercise = admin.createExercise("Admin only", admin.fiveGasProfile().id)
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/exercises/${exercise.id}/events").status)
    }

    @Test
    fun configIsPublic() = withApp {
        val config = browser().get("/api/config").body<ConsoleConfigDto>()
        assertEquals(TEST_CONFIG.version, config.version)
        assertTrue(config.tileUrl.contains("{z}"))
    }

    @Test
    fun publishedAppIsDownloadableWithoutLogin() {
        val dir = Files.createTempDirectory("apk").toFile()
        testApplication {
            TestDatabase.reset()
            val config = TEST_CONFIG.copy(apkDir = dir)
            application { module(config, AppServices.create(TestDatabase.dataSource, config)) }

            assertEquals(HttpStatusCode.NotFound, client.get("/download/simsensor.apk").status)
            assertTrue(client.get("/api/app").bodyAsText().contains("\"available\":false"))

            File(dir, "simsensor.apk").writeBytes(byteArrayOf(1, 2, 3))
            File(dir, "simsensor.json").writeText(
                """{"version":"0.6.0","versionCode":600,"sha256":"abc","sizeBytes":3,"publishedAt":"2026-10-14T00:00:00Z"}""",
            )
            val apk = client.get("/download/simsensor.apk")
            assertEquals(HttpStatusCode.OK, apk.status)
            assertEquals("application/vnd.android.package-archive", apk.headers["Content-Type"])
            assertTrue(apk.headers["Content-Disposition"].orEmpty().contains("simsensor.apk"))
            val info = client.get("/api/app").bodyAsText()
            assertTrue(info.contains("\"available\":true") && info.contains("\"versionCode\":600"), info)
        }
    }

    @Test
    fun consoleFallsBackToIndexButNotForApiOrOutsideFiles() {
        val dir = Files.createTempDirectory("console").toFile()
        File(dir, "index.html").writeText("<html>console</html>")
        File(dir, "assets").mkdirs()
        File(dir, "assets/app-123.js").writeText("console.log(1)")
        File(dir.parentFile, "secret-${UUID.randomUUID()}.txt").apply { writeText("secret") }.deleteOnExit()
        testApplication {
            TestDatabase.reset()
            val config = TEST_CONFIG.copy(consoleDir = dir)
            application { module(config, AppServices.create(TestDatabase.dataSource, config)) }

            assertEquals("<html>console</html>", client.get("/").bodyAsText())
            assertEquals("<html>console</html>", client.get("/exercises/abc").bodyAsText())
            val asset = client.get("/assets/app-123.js")
            assertEquals("console.log(1)", asset.bodyAsText())
            assertTrue(asset.headers["Cache-Control"].orEmpty().contains("max-age"))
            assertEquals(HttpStatusCode.NotFound, client.get("/api/no-such-thing").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/exercises").status, "real API routes still win")
            assertEquals("<html>console</html>", client.get("/..%2Fsecret.txt").bodyAsText(), "no path traversal")
        }
    }
}
