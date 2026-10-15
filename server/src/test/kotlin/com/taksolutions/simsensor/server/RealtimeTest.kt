package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.protocol.ConsoleCodec
import com.taksolutions.simsensor.protocol.ConsoleError
import com.taksolutions.simsensor.protocol.DeviceCommand
import com.taksolutions.simsensor.protocol.CommandKind
import com.taksolutions.simsensor.protocol.DeviceStatus
import com.taksolutions.simsensor.protocol.DeviceTelemetry
import com.taksolutions.simsensor.protocol.ExerciseDevices
import com.taksolutions.simsensor.protocol.ExerciseUpdate
import com.taksolutions.simsensor.protocol.OverrideMode
import com.taksolutions.simsensor.protocol.OverridesUpdate
import com.taksolutions.simsensor.protocol.ProtocolCodec
import com.taksolutions.simsensor.protocol.Telemetry
import com.taksolutions.simsensor.protocol.TelemetrySample
import com.taksolutions.simsensor.protocol.WatchExercise
import com.taksolutions.simsensor.server.api.AffectedDevicesDto
import com.taksolutions.simsensor.server.api.DeviceDto
import com.taksolutions.simsensor.server.api.DeviceTargetDto
import com.taksolutions.simsensor.server.api.ReleaseOverrideRequest
import com.taksolutions.simsensor.server.api.SendCommandRequest
import com.taksolutions.simsensor.server.api.SetOverrideRequest
import com.taksolutions.simsensor.server.realtime.RedisBus
import com.taksolutions.simsensor.server.routes.CONSOLE_SOCKET_PATH
import com.taksolutions.simsensor.server.support.TestRedis
import com.taksolutions.simsensor.server.support.adminBrowser
import com.taksolutions.simsensor.server.support.asDevice
import com.taksolutions.simsensor.server.support.assign
import com.taksolutions.simsensor.server.support.awaitConsole
import com.taksolutions.simsensor.server.support.createExercise
import com.taksolutions.simsensor.server.support.enrollDevice
import com.taksolutions.simsensor.server.support.expectSilence
import com.taksolutions.simsensor.server.support.fiveGasProfile
import com.taksolutions.simsensor.server.support.instructorBrowser
import com.taksolutions.simsensor.server.support.nextConsole
import com.taksolutions.simsensor.server.support.nextDevice
import com.taksolutions.simsensor.server.support.postJson
import com.taksolutions.simsensor.server.support.putJson
import com.taksolutions.simsensor.server.support.withApp
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RealtimeTest {

    @Test
    fun welcomeThenAssignmentThenMonitorChangesReachTheDevice() = withApp {
        val admin = adminBrowser()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        val device = enrollDevice(admin, "Entry 1")

        asDevice(device) { welcome ->
            assertEquals(device.deviceId, welcome.deviceId)
            assertEquals("Entry 1", welcome.deviceName)
            assertNull(welcome.exercise, "New device should be on standby")

            admin.assign(device.deviceId, exercise.id)
            val assigned = nextDevice<ExerciseUpdate>().exercise
            assertNotNull(assigned)
            assertEquals(exercise.id, assigned.exerciseId)
            assertEquals(listOf("O2", "LEL", "CO", "H2S", "HCN"), assigned.profile.channels.map { it.id })
            nextDevice<OverridesUpdate>()

            val stricter = admin.postJson(
                "/api/profiles",
                com.taksolutions.simsensor.profile.DefaultProfiles.FIVE_GAS.copy(
                    name = "Strict",
                    channels = com.taksolutions.simsensor.profile.DefaultProfiles.FIVE_GAS.channels.map {
                        if (it.id == "CO") it.copy(lowAlarm = 25.0) else it
                    },
                ),
            ).body<com.taksolutions.simsensor.server.api.ProfileDto>()
            admin.putJson(
                "/api/exercises/${exercise.id}/devices/monitor",
                com.taksolutions.simsensor.server.api.SetMonitorRequest(listOf(device.deviceId), stricter.id),
            )
            val changed = nextDevice<ExerciseUpdate>().exercise!!
            assertEquals(25.0, changed.profile.channel("CO")!!.lowAlarm)
            assertEquals(assigned.version + 1, changed.version)

            admin.assign(device.deviceId, null)
            assertNull(nextDevice<ExerciseUpdate>().exercise, "Unassigned device should go to standby")
        }
    }

    @Test
    fun overridesReachOnlyTheTargetedExercise() = withApp {
        val admin = adminBrowser()
        val profileId = admin.fiveGasProfile().id
        val exerciseA = admin.createExercise("A", profileId)
        val exerciseB = admin.createExercise("B", profileId)
        val deviceA = enrollDevice(admin, "A1")
        val deviceB = enrollDevice(admin, "B1")
        admin.assign(deviceA.deviceId, exerciseA.id)
        admin.assign(deviceB.deviceId, exerciseB.id)

        asDevice(deviceA) { welcomeA ->
            assertEquals(exerciseA.id, welcomeA.exercise?.exerciseId)
            val sessionA = this
            asDevice(deviceB) {
                val response = admin.postJson(
                    "/api/exercises/${exerciseA.id}/overrides",
                    SetOverrideRequest(DeviceTargetDto(all = true), channelId = "CO", mode = "RAMP", value = 250.0, rampSeconds = 30.0),
                )
                assertEquals(listOf(deviceA.deviceId), response.body<AffectedDevicesDto>().deviceIds)

                val update = sessionA.nextDevice<OverridesUpdate>()
                val override = update.overrides.single()
                assertEquals("CO", override.channelId)
                assertEquals(OverrideMode.RAMP, override.mode)
                assertEquals(250.0, override.value)
                assertEquals(30.0, override.rampSeconds)

                expectSilence() // device B is in another exercise: nothing for it

                admin.postJson(
                    "/api/exercises/${exerciseA.id}/overrides/release",
                    ReleaseOverrideRequest(DeviceTargetDto(deviceIds = listOf(deviceA.deviceId))),
                )
                assertEquals(emptyList(), sessionA.nextDevice<OverridesUpdate>().overrides)
            }
        }
    }

    @Test
    fun overrideValidationAndTargeting() = withApp {
        val admin = adminBrowser()
        val exercise = admin.createExercise("A", admin.fiveGasProfile().id)
        val outsider = enrollDevice(admin, "Elsewhere")
        val url = "/api/exercises/${exercise.id}/overrides"

        val unknownChannel = SetOverrideRequest(DeviceTargetDto(all = true), channelId = "NH3", mode = "FIXED", value = 5.0)
        assertEquals(HttpStatusCode.UnprocessableEntity, admin.postJson(url, unknownChannel).status)
        val rampWithoutTime = SetOverrideRequest(DeviceTargetDto(all = true), channelId = "CO", mode = "RAMP", value = 5.0)
        assertEquals(HttpStatusCode.UnprocessableEntity, admin.postJson(url, rampWithoutTime).status)
        val twoTargets = SetOverrideRequest(DeviceTargetDto(all = true, deviceIds = listOf()), mode = "FAULT")
        assertEquals(HttpStatusCode.UnprocessableEntity, admin.postJson(url, twoTargets).status)
        val notInExercise = SetOverrideRequest(DeviceTargetDto(deviceIds = listOf(outsider.deviceId)), mode = "FAULT")
        assertEquals(HttpStatusCode.UnprocessableEntity, admin.postJson(url, notInExercise).status)
    }

    @Test
    fun instructorsCannotTakeDevicesFromOtherExercises() = withApp {
        val admin = adminBrowser()
        val (alice, _) = instructorBrowser(admin, "alice@example.org")
        val (bob, _) = instructorBrowser(admin, "bob@example.org")
        val alices = alice.createExercise("Alice", alice.fiveGasProfile().id)
        val bobs = bob.createExercise("Bob", bob.fiveGasProfile().id)
        val device = enrollDevice(alice, "Shared phone")

        alice.assign(device.deviceId, alices.id)
        val steal = bob.putJson("/api/devices/${device.deviceId}/exercise", mapOf("exerciseId" to bobs.id))
        assertEquals(HttpStatusCode.Conflict, steal.status)
        val intoAlices = bob.putJson("/api/devices/${device.deviceId}/exercise", mapOf("exerciseId" to alices.id))
        assertEquals(HttpStatusCode.Conflict, intoAlices.status)
        val overrideInAlices = bob.postJson(
            "/api/exercises/${alices.id}/overrides",
            SetOverrideRequest(DeviceTargetDto(all = true), mode = "FAULT"),
        )
        assertEquals(HttpStatusCode.NotFound, overrideInAlices.status)
    }

    @Test
    fun telemetryIsStoredAndStreamedToTheWatchingConsole() = withApp { services ->
        val admin = adminBrowser()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        val device = enrollDevice(admin, "Entry 1")
        admin.assign(device.deviceId, exercise.id)

        admin.webSocket(CONSOLE_SOCKET_PATH) {
            val console = this
            send(Frame.Text(ConsoleCodec.encodeClient(WatchExercise(exercise.id))))
            val snapshot = nextConsole<ExerciseDevices>()
            assertEquals(listOf(device.deviceId), snapshot.devices.map { it.deviceId })

            asDevice(device) {
                assertEquals(DeviceStatus(device.deviceId, online = true), console.awaitConsole<DeviceStatus>())
                val sample = TelemetrySample(
                    ts = System.currentTimeMillis(), lat = 33.12, lon = -117.31, battery = 0.8f,
                    readings = mapOf("O2" to 20.9, "CO" to 42.0), alarms = listOf("CO:LOW"),
                )
                send(Frame.Text(ProtocolCodec.encode(Telemetry(listOf(sample)))))
                assertEquals(DeviceTelemetry(device.deviceId, sample), console.awaitConsole<DeviceTelemetry>())

                services.telemetryWriter.flush()
                val stored = services.telemetryRepository.forDevice(UUID.fromString(device.deviceId)).single()
                assertEquals(sample.readings, stored.readings)
                assertEquals(sample.alarms, stored.alarms)
                assertEquals(33.12, stored.lat)
            }
            assertEquals(DeviceStatus(device.deviceId, online = false), console.awaitConsole<DeviceStatus>())
        }
        val listed = admin.get("/api/exercises/${exercise.id}/devices").body<List<DeviceDto>>().single()
        assertNotNull(listed.lastSeenAt)
    }

    @Test
    fun consoleCannotWatchAnotherInstructorsExercise() = withApp {
        val admin = adminBrowser()
        val (alice, _) = instructorBrowser(admin, "alice@example.org")
        val (bob, _) = instructorBrowser(admin, "bob@example.org")
        val alices = alice.createExercise("Alice", alice.fiveGasProfile().id)

        bob.webSocket(CONSOLE_SOCKET_PATH) {
            send(Frame.Text(ConsoleCodec.encodeClient(WatchExercise(alices.id))))
            assertEquals("not_found", nextConsole<ConsoleError>().code)
        }
    }

    @Test
    fun commandsReachTheDevice() = withApp {
        val admin = adminBrowser()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        val device = enrollDevice(admin, "Entry 1")
        admin.assign(device.deviceId, exercise.id)

        asDevice(device) {
            val request = SendCommandRequest(DeviceTargetDto(all = true), kind = "SHOW_MESSAGE", text = "Exit the hot zone")
            assertEquals(HttpStatusCode.OK, admin.postJson("/api/exercises/${exercise.id}/commands", request).status)
            assertEquals(DeviceCommand(CommandKind.SHOW_MESSAGE, "Exit the hot zone"), nextDevice<DeviceCommand>())

            val empty = SendCommandRequest(DeviceTargetDto(all = true), kind = "SHOW_MESSAGE", text = " ")
            assertEquals(HttpStatusCode.UnprocessableEntity, admin.postJson("/api/exercises/${exercise.id}/commands", empty).status)
        }
    }

    @Test
    fun theFullFlowWorksOverRedis() = withApp(bus = RedisBus(TestRedis.url)) {
        val admin = adminBrowser()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        val device = enrollDevice(admin, "Entry 1")
        asDevice(device) {
            admin.assign(device.deviceId, exercise.id)
            assertEquals(exercise.id, nextDevice<ExerciseUpdate>().exercise?.exerciseId)
            nextDevice<OverridesUpdate>()
            admin.postJson("/api/exercises/${exercise.id}/overrides", SetOverrideRequest(DeviceTargetDto(all = true), mode = "FAULT"))
            assertEquals(OverrideMode.FAULT, nextDevice<OverridesUpdate>().overrides.single().mode)
        }
    }
}
