package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.protocol.ExerciseUpdate
import com.taksolutions.simsensor.server.api.ErrorResponse
import com.taksolutions.simsensor.server.routes.BeaconDto
import com.taksolutions.simsensor.server.sources.PreviewGrid
import com.taksolutions.simsensor.server.support.adminBrowser
import com.taksolutions.simsensor.server.support.asDevice
import com.taksolutions.simsensor.server.support.assign
import com.taksolutions.simsensor.server.support.createExercise
import com.taksolutions.simsensor.server.support.enrollDevice
import com.taksolutions.simsensor.server.support.fiveGasProfile
import com.taksolutions.simsensor.server.support.instructorBrowser
import com.taksolutions.simsensor.server.support.nextDevice
import com.taksolutions.simsensor.server.support.postJson
import com.taksolutions.simsensor.server.support.withApp
import com.taksolutions.simsensor.source.BeaconShape
import com.taksolutions.simsensor.source.GasRelease
import com.taksolutions.simsensor.source.GasSource
import com.taksolutions.simsensor.source.LatLon
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Beacon inventory and beacon leaks (Phase 3, chunk 3.1). */
class BeaconsApiTest {
    private val drum = BeaconDto(label = "Drum 1", uuid = "FDA50693-A4E2-4FB1-AFCF-C6EB07647825", major = 10001, minor = 19641)
    private val site = LatLon(32.7157, -117.1611)

    private suspend fun HttpClient.createBeacon(dto: BeaconDto = drum): BeaconDto = postJson("/api/beacons", dto).body()

    private fun beaconLeak(beaconId: String, name: String = "Drum leak") = GasSource(
        id = "", name = name, shape = BeaconShape(beaconId, radiusM = 8.0, anchor = site),
        gases = listOf(GasRelease("H2S", 40.0)),
    )

    @Test
    fun adminsKeepTheInventory() = withApp {
        val admin = adminBrowser()
        val created = admin.createBeacon()
        assertEquals("fda50693-a4e2-4fb1-afcf-c6eb07647825", created.uuid, "UUID stored in lower case")
        assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/beacons", drum.copy(label = "Same radio")).status)
        val bad = admin.postJson("/api/beacons", drum.copy(uuid = "not-a-uuid", major = 70000))
        assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
        assertTrue(bad.body<ErrorResponse>().errors.map { it.field }.containsAll(listOf("uuid", "major")))

        val (bob, _) = instructorBrowser(admin, "bob@example.org")
        assertEquals(listOf("Drum 1"), bob.get("/api/beacons").body<List<BeaconDto>>().map { it.label })
        assertEquals(HttpStatusCode.Forbidden, bob.postJson("/api/beacons", drum.copy(minor = 2)).status)
    }

    @Test
    fun beaconLeaksReachPhonesWithTheirBeacons() = withApp {
        val admin = adminBrowser()
        val beacon = admin.createBeacon()
        val exercise = admin.createExercise("Confined space", admin.fiveGasProfile().id)
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            admin.postJson("/api/exercises/${exercise.id}/sources", beaconLeak("00000000-0000-0000-0000-000000000000")).status,
        )
        val device = enrollDevice(admin, "Entry 1")
        admin.assign(device.deviceId, exercise.id)
        asDevice(device) { welcome ->
            assertTrue(welcome.exercise!!.beacons.isEmpty())
            assertEquals(HttpStatusCode.Created, admin.postJson("/api/exercises/${exercise.id}/sources", beaconLeak(beacon.id)).status)
            val update = nextDevice<ExerciseUpdate>().exercise!!
            assertEquals(listOf(beacon.id), update.beacons.map { it.id })
            assertEquals(10001, update.beacons.single().major)
            assertTrue(update.sources.single().shape is BeaconShape)
        }
    }

    @Test
    fun aBeaconServesOneRunningExercise() = withApp {
        val admin = adminBrowser()
        val beacon = admin.createBeacon()
        val profileId = admin.fiveGasProfile().id
        val first = admin.createExercise("Morning", profileId)
        val second = admin.createExercise("Afternoon", profileId)
        admin.postJson("/api/exercises/${first.id}/sources", beaconLeak(beacon.id))
        admin.post("/api/exercises/${first.id}/start")

        // Planning the second exercise with the same beacon is fine; running both at once is not.
        assertEquals(HttpStatusCode.Created, admin.postJson("/api/exercises/${second.id}/sources", beaconLeak(beacon.id)).status)
        val clash = admin.post("/api/exercises/${second.id}/start")
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertTrue(clash.body<ErrorResponse>().message.contains("Morning"))

        admin.post("/api/exercises/${first.id}/end")
        assertEquals(HttpStatusCode.OK, admin.post("/api/exercises/${second.id}/start").status)
        // And now the first can't add the beacon back while the second runs.
        admin.post("/api/exercises/${first.id}/start").let { assertEquals(HttpStatusCode.Conflict, it.status) }
    }

    @Test
    fun beaconInUseCannotBeDeleted() = withApp {
        val admin = adminBrowser()
        val beacon = admin.createBeacon()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        val leak = admin.postJson("/api/exercises/${exercise.id}/sources", beaconLeak(beacon.id)).body<GasSource>()
        assertEquals(HttpStatusCode.Conflict, admin.delete("/api/beacons/${beacon.id}").status)
        admin.delete("/api/exercises/${exercise.id}/sources/${leak.id}")
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/beacons/${beacon.id}").status)
    }

    @Test
    fun previewDrawsBeaconLeaksAroundTheirPin() = withApp {
        val admin = adminBrowser()
        val beacon = admin.createBeacon()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        admin.postJson("/api/exercises/${exercise.id}/sources", beaconLeak(beacon.id))
        val grid = admin.get(
            "/api/exercises/${exercise.id}/preview?gas=H2S&south=32.7155&west=-117.1613&north=32.7159&east=-117.1609&cells=20",
        ).body<PreviewGrid>()
        assertTrue(grid.max > 30.0, "planning view shows the beacon leak near its pin (max ${grid.max})")
    }
}
