package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.profile.DefaultProfiles
import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.protocol.ExerciseUpdate
import com.taksolutions.simsensor.server.api.DeviceDto
import com.taksolutions.simsensor.server.api.DeviceTargetDto
import com.taksolutions.simsensor.server.api.EventDto
import com.taksolutions.simsensor.server.api.ExerciseDto
import com.taksolutions.simsensor.server.api.ProfileDto
import com.taksolutions.simsensor.server.api.SetMonitorRequest
import com.taksolutions.simsensor.server.api.SetOverrideRequest
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
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Monitors (chunk 2.7): each phone in an exercise imitates its own monitor. */
class MonitorsApiTest {

    /** A 4-gas monitor without HCN, with its own CO alarm levels. */
    private val fourGas: SensorProfile = DefaultProfiles.FIVE_GAS.copy(
        name = "Blackline G8",
        model = "Blackline Safety G8",
        channels = DefaultProfiles.FIVE_GAS.channels.filter { it.id != "HCN" }.map {
            if (it.id == "CO") it.copy(lowAlarm = 25.0, highAlarm = 100.0) else it
        },
    )

    private suspend fun HttpClient.createMonitor(definition: SensorProfile = fourGas): ProfileDto =
        postJson("/api/profiles", definition).body()

    @Test
    fun eachPhoneGetsItsOwnMonitor() = withApp {
        val admin = adminBrowser()
        val g8 = admin.createMonitor()
        val exercise = admin.createExercise("Mixed fleet", admin.fiveGasProfile().id)
        val first = enrollDevice(admin, "Entry 1")
        val second = enrollDevice(admin, "Entry 2")
        admin.assign(first.deviceId, exercise.id)
        admin.assign(second.deviceId, exercise.id)

        asDevice(second) { welcome ->
            assertEquals("5-Gas", welcome.exercise!!.profile.name, "no monitor set: the exercise default")
            val set = admin.putJson("/api/exercises/${exercise.id}/devices/monitor", SetMonitorRequest(listOf(second.deviceId), g8.id))
            assertEquals(HttpStatusCode.OK, set.status)
            assertEquals(g8.id, set.body<List<DeviceDto>>().single().monitorId)

            val update = nextDevice<ExerciseUpdate>().exercise!!
            assertEquals("Blackline G8", update.profile.name)
            assertEquals("Blackline Safety G8", update.profile.model)
            assertEquals(25.0, update.profile.channel("CO")!!.lowAlarm)
            assertNull(update.profile.channel("HCN"))
        }
        asDevice(first) { welcome ->
            assertEquals("5-Gas", welcome.exercise!!.profile.name, "the other phone keeps the default")
        }

        val monitors = admin.get("/api/exercises/${exercise.id}/monitors").body<List<ProfileDto>>()
        assertEquals(listOf("5-Gas", "Blackline G8"), monitors.map { it.definition.name }, "default first")
        val log = admin.get("/api/exercises/${exercise.id}/events").body<List<EventDto>>()
        assertEquals("Blackline G8", log.first { it.type == "device.monitor" }.data["monitor"])
    }

    @Test
    fun defaultMonitorChangeReachesPhonesWithoutTheirOwn() = withApp {
        val admin = adminBrowser()
        val g8 = admin.createMonitor()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        val device = enrollDevice(admin, "Entry 1")
        admin.assign(device.deviceId, exercise.id)
        asDevice(device) {
            admin.patchJson("/api/exercises/${exercise.id}", mapOf("profileId" to g8.id))
            assertEquals("Blackline G8", nextDevice<ExerciseUpdate>().exercise!!.profile.name)
        }
    }

    @Test
    fun editingAMonitorReachesThePhonesUsingIt() = withApp {
        val admin = adminBrowser()
        val g8 = admin.createMonitor()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        val device = enrollDevice(admin, "Entry 1")
        admin.assign(device.deviceId, exercise.id)
        admin.putJson("/api/exercises/${exercise.id}/devices/monitor", SetMonitorRequest(listOf(device.deviceId), g8.id))
        val before = admin.get("/api/exercises/${exercise.id}").body<ExerciseDto>().version

        asDevice(device) {
            val changed = fourGas.copy(channels = fourGas.channels.map { if (it.id == "CO") it.copy(lowAlarm = 20.0) else it })
            assertEquals(HttpStatusCode.OK, admin.putJson("/api/profiles/${g8.id}", changed).status)
            assertEquals(20.0, nextDevice<ExerciseUpdate>().exercise!!.profile.channel("CO")!!.lowAlarm)
        }
        assertEquals(before + 1, admin.get("/api/exercises/${exercise.id}").body<ExerciseDto>().version)
    }

    @Test
    fun movingAPhoneToAnotherExerciseClearsItsMonitor() = withApp {
        val admin = adminBrowser()
        val g8 = admin.createMonitor()
        val profileId = admin.fiveGasProfile().id
        val first = admin.createExercise("First", profileId)
        val second = admin.createExercise("Second", profileId)
        val device = enrollDevice(admin, "Entry 1")
        admin.assign(device.deviceId, first.id)
        admin.putJson("/api/exercises/${first.id}/devices/monitor", SetMonitorRequest(listOf(device.deviceId), g8.id))

        admin.assign(device.deviceId, second.id)
        val moved = admin.get("/api/exercises/${second.id}/devices").body<List<DeviceDto>>().single()
        assertNull(moved.monitorId, "a new exercise starts from its default monitor")
    }

    @Test
    fun monitorInUseCannotBeDeleted() = withApp {
        val admin = adminBrowser()
        val g8 = admin.createMonitor()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        val device = enrollDevice(admin, "Entry 1")
        admin.assign(device.deviceId, exercise.id)
        admin.putJson("/api/exercises/${exercise.id}/devices/monitor", SetMonitorRequest(listOf(device.deviceId), g8.id))
        assertEquals(HttpStatusCode.Conflict, admin.delete("/api/profiles/${g8.id}").status)

        admin.putJson("/api/exercises/${exercise.id}/devices/monitor", SetMonitorRequest(listOf(device.deviceId), null))
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/profiles/${g8.id}").status)
    }

    @Test
    fun overridesAcceptAnyGasOfTheExercisesMonitors() = withApp {
        val admin = adminBrowser()
        val g8 = admin.createMonitor()
        // Default monitor without HCN; one phone on the 5-gas monitor, which has it.
        val exercise = admin.createExercise("Drill", g8.id)
        val device = enrollDevice(admin, "Entry 1")
        admin.assign(device.deviceId, exercise.id)
        val hcn = SetOverrideRequest(DeviceTargetDto(all = true), channelId = "HCN", mode = "FIXED", value = 5.0)
        assertEquals(HttpStatusCode.UnprocessableEntity, admin.postJson("/api/exercises/${exercise.id}/overrides", hcn).status)

        admin.putJson("/api/exercises/${exercise.id}/devices/monitor", SetMonitorRequest(listOf(device.deviceId), admin.fiveGasProfile().id))
        assertEquals(HttpStatusCode.OK, admin.postJson("/api/exercises/${exercise.id}/overrides", hcn).status)
    }

    @Test
    fun monitorsCanOnlyBeSetOnPhonesInTheExercise() = withApp {
        val admin = adminBrowser()
        val g8 = admin.createMonitor()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        val outsider = enrollDevice(admin, "Elsewhere")
        val response = admin.putJson("/api/exercises/${exercise.id}/devices/monitor", SetMonitorRequest(listOf(outsider.deviceId), g8.id))
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)

        val (bob, _) = instructorBrowser(admin, "bob@example.org")
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/exercises/${exercise.id}/monitors").status)
    }

    @Test
    fun monitorKeepsItsModel() = withApp {
        val admin = adminBrowser()
        val created = admin.createMonitor(fourGas.copy(name = "X-am 2800", model = "Dräger X-am 2800"))
        assertEquals("Dräger X-am 2800", admin.get("/api/profiles/${created.id}").body<ProfileDto>().definition.model)
        val badScreen = admin.postJson("/api/profiles", fourGas.copy(name = "Odd", screen = "lcd-9000"))
        assertEquals(HttpStatusCode.UnprocessableEntity, badScreen.status)
    }
}
