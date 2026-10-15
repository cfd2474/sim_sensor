package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.profile.DefaultProfiles
import com.taksolutions.simsensor.protocol.ExerciseUpdate
import com.taksolutions.simsensor.protocol.SettingsUpdate
import com.taksolutions.simsensor.protocol.Units
import com.taksolutions.simsensor.server.api.ErrorResponse
import com.taksolutions.simsensor.server.api.ExerciseDto
import com.taksolutions.simsensor.server.api.ProfileDto
import com.taksolutions.simsensor.server.settings.SystemSettings
import com.taksolutions.simsensor.server.sources.PreviewGrid
import com.taksolutions.simsensor.server.support.MutableClock
import com.taksolutions.simsensor.server.support.adminBrowser
import com.taksolutions.simsensor.server.support.asDevice
import com.taksolutions.simsensor.server.support.assign
import com.taksolutions.simsensor.server.support.createExercise
import com.taksolutions.simsensor.server.support.enrollDevice
import com.taksolutions.simsensor.server.support.fiveGasProfile
import com.taksolutions.simsensor.server.support.instructorBrowser
import com.taksolutions.simsensor.server.support.nextDevice
import com.taksolutions.simsensor.server.support.patchJson
import com.taksolutions.simsensor.server.support.postJson
import com.taksolutions.simsensor.server.support.putJson
import com.taksolutions.simsensor.server.support.withApp
import com.taksolutions.simsensor.source.Activation
import com.taksolutions.simsensor.source.GasRelease
import com.taksolutions.simsensor.source.GasSource
import com.taksolutions.simsensor.source.LatLon
import com.taksolutions.simsensor.source.PointShape
import com.taksolutions.simsensor.source.SourceTiming
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.time.Duration
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourcesApiTest {
    private val site = LatLon(32.7157, -117.1611)
    private fun leak(name: String = "Tank", gas: String = "CO", peak: Double = 300.0, timing: SourceTiming = SourceTiming()) =
        GasSource(id = "", name = name, shape = PointShape(site, 100.0), gases = listOf(GasRelease(gas, peak)), timing = timing)

    @Test
    fun leaksAreValidatedStoredAndReachThePhones() = withApp {
        val admin = adminBrowser()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        val device = enrollDevice(admin, "Entry 1")
        admin.assign(device.deviceId, exercise.id)
        val base = "/api/exercises/${exercise.id}/sources"

        asDevice(device) { welcome ->
            assertEquals(emptyList(), welcome.exercise?.sources)

            val bad = admin.postJson(base, leak(gas = "NH3"))
            assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
            assertTrue(bad.body<ErrorResponse>().errors.any { it.channelId == "NH3" })

            val created = admin.postJson(base, leak()).body<GasSource>()
            assertTrue(created.id.isNotBlank())
            val pushed = nextDevice<ExerciseUpdate>().exercise!!
            assertEquals(listOf("Tank"), pushed.sources.map { it.name })

            val moved = created.copy(shape = PointShape(LatLon(32.72, -117.16), 150.0))
            assertEquals(150.0, (admin.putJson("$base/${created.id}", moved).body<GasSource>().shape as PointShape).radiusM)
            assertEquals(150.0, (nextDevice<ExerciseUpdate>().exercise!!.sources.single().shape as PointShape).radiusM)

            assertEquals(HttpStatusCode.NoContent, admin.delete("$base/${created.id}").status)
            assertEquals(emptyList(), nextDevice<ExerciseUpdate>().exercise!!.sources)
        }
    }

    @Test
    fun otherInstructorsCantSeeOrChangeLeaks() = withApp {
        val admin = adminBrowser()
        val (bob, _) = instructorBrowser(admin, "bob@example.org")
        val exercise = admin.createExercise("Admin's", admin.fiveGasProfile().id)
        val created = admin.postJson("/api/exercises/${exercise.id}/sources", leak()).body<GasSource>()
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/exercises/${exercise.id}/sources").status)
        assertEquals(HttpStatusCode.NotFound, bob.delete("/api/exercises/${exercise.id}/sources/${created.id}").status)
        // A leak id from another exercise is not found under this one.
        val other = admin.createExercise("Other", admin.fiveGasProfile().id)
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/exercises/${other.id}/sources/${created.id}").status)
    }

    @Test
    fun theClockRunsPausesAndResets() {
        val clock = MutableClock()
        withApp(clock = clock) {
            val admin = adminBrowser()
            val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
            val device = enrollDevice(admin, "Entry 1")
            admin.assign(device.deviceId, exercise.id)
            asDevice(device) { welcome ->
                assertNull(welcome.exercise?.clock, "a draft exercise has no clock")

                admin.post("/api/exercises/${exercise.id}/start")
                val started = nextDevice<ExerciseUpdate>().exercise!!.clock!!
                assertEquals(0.0, started.elapsedS)
                assertTrue(started.running)

                clock.now = clock.now.plus(Duration.ofSeconds(90))
                admin.post("/api/exercises/${exercise.id}/pause")
                val paused = nextDevice<ExerciseUpdate>().exercise!!.clock!!
                assertEquals(90.0, paused.elapsedS, 0.01)
                assertTrue(!paused.running)

                clock.now = clock.now.plus(Duration.ofMinutes(10)) // paused time doesn't count
                admin.post("/api/exercises/${exercise.id}/resume")
                nextDevice<ExerciseUpdate>()
                clock.now = clock.now.plus(Duration.ofSeconds(30))
                val dto = admin.get("/api/exercises/${exercise.id}").body<ExerciseDto>()
                assertEquals(90.0, dto.clockElapsedS, 0.01)
                assertNotNull(dto.clockResumedAt)

                admin.post("/api/exercises/${exercise.id}/pause")
                assertEquals(120.0, nextDevice<ExerciseUpdate>().exercise!!.clock!!.elapsedS, 0.01)

                admin.post("/api/exercises/${exercise.id}/reset")
                nextDevice<com.taksolutions.simsensor.protocol.OverridesUpdate>() // reset releases overrides first
                assertNull(nextDevice<ExerciseUpdate>().exercise!!.clock)
            }
        }
    }

    @Test
    fun manualLeaksStartWhenActivated() {
        val clock = MutableClock()
        withApp(clock = clock) {
            val admin = adminBrowser()
            val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
            val base = "/api/exercises/${exercise.id}/sources"
            val manual = admin.postJson(base, leak(timing = SourceTiming(Activation.MANUAL))).body<GasSource>()
            val immediate = admin.postJson(base, leak(name = "Always")).body<GasSource>()

            assertEquals(HttpStatusCode.Conflict, admin.post("$base/${manual.id}/activate").status, "not started yet")
            admin.post("/api/exercises/${exercise.id}/start")
            clock.now = clock.now.plus(Duration.ofSeconds(45))
            assertEquals(HttpStatusCode.Conflict, admin.post("$base/${immediate.id}/activate").status, "not a manual leak")
            val activated = admin.post("$base/${manual.id}/activate").body<GasSource>()
            assertEquals(45.0, activated.activatedAtS!!, 0.01)

            // A new run clears manual activations.
            admin.post("/api/exercises/${exercise.id}/end")
            admin.post("/api/exercises/${exercise.id}/start")
            assertNull(admin.get(base).body<List<GasSource>>().first { it.id == manual.id }.activatedAtS)
        }
    }

    @Test
    fun previewUsesTheSameModelAsThePhones() = withApp {
        val admin = adminBrowser()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        val base = "/api/exercises/${exercise.id}/sources"
        admin.postJson(base, leak(peak = 300.0, timing = SourceTiming(Activation.MANUAL)))
        val box = "south=32.7130&west=-117.1640&north=32.7184&east=-117.1582"

        // Draft: planning view, every enabled leak at full strength (even manual ones).
        val draft = admin.get("/api/exercises/${exercise.id}/preview?gas=CO&$box&cells=41").body<PreviewGrid>()
        assertNull(draft.atS)
        assertTrue(abs(draft.max - 300.0) < 30, "peak near the centre: ${draft.max}")
        assertEquals(0.0, draft.values.first(), "corner is outside the 100 m radius")
        assertEquals(draft.rows * draft.cols, draft.values.size)

        // Running: the manual leak hasn't been activated, so nothing yet.
        admin.post("/api/exercises/${exercise.id}/start")
        val live = admin.get("/api/exercises/${exercise.id}/preview?gas=CO&$box").body<PreviewGrid>()
        assertNotNull(live.atS)
        assertEquals(0.0, live.max)

        assertEquals(HttpStatusCode.UnprocessableEntity, admin.get("/api/exercises/${exercise.id}/preview?gas=NH3&$box").status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/exercises/${exercise.id}/preview?gas=CO").status)
    }

    @Test
    fun leaksMayUseGasesTheExercisesMonitorsLack() = withApp {
        // A leak describes the hazard; phones whose monitor lacks the gas just don't show it.
        val admin = adminBrowser()
        val noHcn = DefaultProfiles.FIVE_GAS.let { it.copy(name = "4-Gas", channels = it.channels.filter { c -> c.id != "HCN" }) }
        val fourGas = admin.postJson("/api/profiles", noHcn).body<ProfileDto>()
        val exercise = admin.createExercise("Drill", fourGas.id)
        assertEquals(HttpStatusCode.Created, admin.postJson("/api/exercises/${exercise.id}/sources", leak(gas = "HCN", peak = 20.0)).status)
        assertEquals(HttpStatusCode.UnprocessableEntity, admin.postJson("/api/exercises/${exercise.id}/sources", leak(gas = "NH3", peak = 20.0)).status)
        // No monitor in the exercise shows HCN, so there is nothing to preview for it.
        val preview = admin.get("/api/exercises/${exercise.id}/preview?gas=HCN&south=32.71&west=-117.17&north=32.72&east=-117.16")
        assertEquals(HttpStatusCode.UnprocessableEntity, preview.status)
    }

    @Test
    fun unitsAreAnAdminSettingThatReachesPhones() = withApp {
        val admin = adminBrowser()
        val (bob, _) = instructorBrowser(admin, "bob@example.org")
        assertEquals(Units.IMPERIAL, bob.get("/api/settings").body<SystemSettings>().units)
        assertEquals(HttpStatusCode.Forbidden, bob.putJson("/api/settings", SystemSettings(Units.METRIC)).status)

        val device = enrollDevice(admin, "Entry 1")
        asDevice(device) { welcome ->
            assertEquals(Units.IMPERIAL, welcome.units)
            assertEquals(Units.METRIC, admin.putJson("/api/settings", SystemSettings(Units.METRIC)).body<SystemSettings>().units)
            assertEquals(Units.METRIC, nextDevice<SettingsUpdate>().units)
        }
        asDevice(device) { welcome -> assertEquals(Units.METRIC, welcome.units) }
    }
}
