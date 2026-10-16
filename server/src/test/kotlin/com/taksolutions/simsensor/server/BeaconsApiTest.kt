package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.protocol.ExerciseUpdate
import com.taksolutions.simsensor.server.api.ErrorResponse
import com.taksolutions.simsensor.server.routes.BeaconDto
import com.taksolutions.simsensor.server.routes.BeaconUseDto
import com.taksolutions.simsensor.server.routes.CalibrateBeaconRequest
import com.taksolutions.simsensor.server.routes.CalibrateBeaconResponse
import com.taksolutions.simsensor.server.routes.CalibrationPointDto
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
import com.taksolutions.simsensor.server.support.putJson
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
        // As beacon apps show it: no dashes, upper case. Stored in the standard form.
        val bare = admin.createBeacon(drum.copy(label = "Bare", uuid = "EB14B62F218B4EEE8F15B6322ADBB54E", minor = 7))
        assertEquals("eb14b62f-218b-4eee-8f15-b6322adbb54e", bare.uuid)
        // The same beacon typed with dashes is recognised as a duplicate.
        assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/beacons", drum.copy(label = "Dup", uuid = "eb14b62f-218b-4eee-8f15-b6322adbb54e", minor = 7)).status)
        assertEquals(HttpStatusCode.UnprocessableEntity, admin.postJson("/api/beacons", drum.copy(uuid = "EB14B62F218B4EEE8F15B6322ADBB5", minor = 8)).status, "31 digits")
        val bad = admin.postJson("/api/beacons", drum.copy(uuid = "not-a-uuid", major = 70000))
        assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
        assertTrue(bad.body<ErrorResponse>().errors.map { it.field }.containsAll(listOf("uuid", "major")))

        val (bob, _) = instructorBrowser(admin, "bob@example.org")
        assertEquals(listOf("Bare", "Drum 1"), bob.get("/api/beacons").body<List<BeaconDto>>().map { it.label })
        assertEquals(HttpStatusCode.Forbidden, bob.postJson("/api/beacons", drum.copy(minor = 2)).status)
    }

    private suspend fun HttpClient.addToExercise(exerciseId: String, beaconId: String) =
        postJson("/api/exercises/$exerciseId/beacons", mapOf("beaconId" to beaconId))

    @Test
    fun phonesGetTheExercisesBeaconsOnly() = withApp {
        val admin = adminBrowser()
        val drumBeacon = admin.createBeacon()
        val other = admin.createBeacon(drum.copy(label = "Elsewhere", minor = 2))
        val exercise = admin.createExercise("Confined space", admin.fiveGasProfile().id)
        // A beacon leak needs the beacon in the exercise first.
        assertEquals(HttpStatusCode.UnprocessableEntity, admin.postJson("/api/exercises/${exercise.id}/sources", beaconLeak(drumBeacon.id)).status)

        val device = enrollDevice(admin, "Entry 1")
        admin.assign(device.deviceId, exercise.id)
        asDevice(device) { welcome ->
            assertTrue(welcome.exercise!!.beacons.isEmpty())
            val listed = admin.addToExercise(exercise.id, drumBeacon.id).body<List<BeaconDto>>()
            assertEquals(listOf("Drum 1"), listed.map { it.label })
            // In the exercise without any leak yet: the phone already knows it (and only it).
            assertEquals(listOf(drumBeacon.id), nextDevice<ExerciseUpdate>().exercise!!.beacons.map { it.id })
            assertEquals(HttpStatusCode.Created, admin.postJson("/api/exercises/${exercise.id}/sources", beaconLeak(drumBeacon.id)).status)
            val update = nextDevice<ExerciseUpdate>().exercise!!
            assertEquals(listOf(drumBeacon.id), update.beacons.map { it.id }, "${other.label} is not in the exercise")
            assertEquals(10001, update.beacons.single().major)
            assertTrue(update.sources.single().shape is BeaconShape)
        }
        assertEquals("Drum 1", admin.get("/api/exercises/${exercise.id}/events").body<List<com.taksolutions.simsensor.server.api.EventDto>>()
            .first { it.type == "beacon.added" }.data["beacon"])
    }

    @Test
    fun editingABeaconReachesPhonesOfItsExercises() = withApp {
        val admin = adminBrowser()
        val beacon = admin.createBeacon()
        val exercise = admin.createExercise("Confined space", admin.fiveGasProfile().id)
        admin.addToExercise(exercise.id, beacon.id)
        val device = enrollDevice(admin, "Entry 1")
        admin.assign(device.deviceId, exercise.id)
        asDevice(device) { welcome ->
            assertEquals(19641, welcome.exercise!!.beacons.single().minor)
            // Reprogrammed beacon: the phone must listen for the new identity.
            assertEquals(HttpStatusCode.OK, admin.putJson("/api/beacons/${beacon.id}", beacon.copy(minor = 7)).status)
            assertEquals(7, nextDevice<ExerciseUpdate>().exercise!!.beacons.single().minor)
        }
    }

    @Test
    fun calibrationIsFittedSavedAndSentToPhones() = withApp {
        val admin = adminBrowser()
        val beacon = admin.createBeacon()
        val exercise = admin.createExercise("Confined space", admin.fiveGasProfile().id)
        admin.addToExercise(exercise.id, beacon.id)
        assertEquals(listOf("Confined space"), admin.get("/api/beacons/${beacon.id}/exercises").body<List<BeaconUseDto>>().map { it.exerciseName })
        val device = enrollDevice(admin, "Entry 1")
        admin.assign(device.deviceId, exercise.id)
        asDevice(device) { _ ->
            val points = CalibrateBeaconRequest(listOf(CalibrationPointDto(1.0, -59.0), CalibrationPointDto(10.0, -92.0)))
            val result = admin.postJson("/api/beacons/${beacon.id}/calibrate", points).body<CalibrateBeaconResponse>()
            assertEquals(-59.0, result.beacon.txPowerAt1m)
            assertEquals(3.3, result.beacon.pathLossN)
            val sent = nextDevice<ExerciseUpdate>().exercise!!.beacons.single()
            assertEquals(-59.0, sent.txPowerAt1m)
            assertEquals(3.3, sent.pathLossN)
        }
        val bad = admin.postJson("/api/beacons/${beacon.id}/calibrate", CalibrateBeaconRequest(listOf(CalibrationPointDto(0.0, 5.0))))
        assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
        val (bob, _) = instructorBrowser(admin, "bob@example.org")
        assertEquals(HttpStatusCode.Forbidden, bob.postJson("/api/beacons/${beacon.id}/calibrate", CalibrateBeaconRequest(listOf(CalibrationPointDto(1.0, -60.0)))).status)
    }

    @Test
    fun aBeaconCanServeSeveralExercisesAtOnce() = withApp {
        val admin = adminBrowser()
        val beacon = admin.createBeacon()
        val profileId = admin.fiveGasProfile().id
        val first = admin.createExercise("Morning", profileId)
        val second = admin.createExercise("Afternoon", profileId)
        for (x in listOf(first, second)) {
            admin.addToExercise(x.id, beacon.id)
            admin.postJson("/api/exercises/${x.id}/sources", beaconLeak(beacon.id))
            assertEquals(HttpStatusCode.OK, admin.post("/api/exercises/${x.id}/start").status, "${x.name} starts")
        }
    }

    @Test
    fun beaconsLeaveExercisesOnlyWithoutLeaks() = withApp {
        val admin = adminBrowser()
        val beacon = admin.createBeacon()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        admin.addToExercise(exercise.id, beacon.id)
        val leak = admin.postJson("/api/exercises/${exercise.id}/sources", beaconLeak(beacon.id)).body<GasSource>()

        assertEquals(HttpStatusCode.Conflict, admin.delete("/api/exercises/${exercise.id}/beacons/${beacon.id}").status, "a leak uses it")
        assertEquals(HttpStatusCode.Conflict, admin.delete("/api/beacons/${beacon.id}").status, "in an exercise")
        admin.delete("/api/exercises/${exercise.id}/sources/${leak.id}")
        assertEquals(HttpStatusCode.OK, admin.delete("/api/exercises/${exercise.id}/beacons/${beacon.id}").status)
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/beacons/${beacon.id}").status)
    }

    @Test
    fun onlyTheExercisesInstructorsManageItsBeacons() = withApp {
        val admin = adminBrowser()
        val beacon = admin.createBeacon()
        val exercise = admin.createExercise("Admin only", admin.fiveGasProfile().id)
        val (bob, _) = instructorBrowser(admin, "bob@example.org")
        assertEquals(HttpStatusCode.NotFound, bob.addToExercise(exercise.id, beacon.id).status)
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/exercises/${exercise.id}/beacons").status)
    }

    @Test
    fun previewDrawsBeaconLeaksAroundTheirPin() = withApp {
        val admin = adminBrowser()
        val beacon = admin.createBeacon()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        admin.addToExercise(exercise.id, beacon.id)
        admin.postJson("/api/exercises/${exercise.id}/sources", beaconLeak(beacon.id))
        val grid = admin.get(
            "/api/exercises/${exercise.id}/preview?gas=H2S&south=32.7155&west=-117.1613&north=32.7159&east=-117.1609&cells=20",
        ).body<PreviewGrid>()
        assertTrue(grid.max > 30.0, "planning view shows the beacon leak near its pin (max ${grid.max})")
    }
}
