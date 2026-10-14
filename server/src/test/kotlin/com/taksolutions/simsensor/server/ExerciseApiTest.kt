package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.profile.ChannelOverride
import com.taksolutions.simsensor.profile.CriteriaOverrides
import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.server.api.ErrorResponse
import com.taksolutions.simsensor.server.api.ExerciseDto
import com.taksolutions.simsensor.server.api.SetInstructorsRequest
import com.taksolutions.simsensor.server.support.adminBrowser
import com.taksolutions.simsensor.server.support.createExercise
import com.taksolutions.simsensor.server.support.fiveGasProfile
import com.taksolutions.simsensor.server.support.instructorBrowser
import com.taksolutions.simsensor.server.support.patchJson
import com.taksolutions.simsensor.server.support.postJson
import com.taksolutions.simsensor.server.support.putJson
import com.taksolutions.simsensor.server.support.withApp
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExerciseApiTest {

    @Test
    fun instructorsOnlyReachTheirOwnExercises() = withApp {
        val admin = adminBrowser()
        val (alice, _) = instructorBrowser(admin, "alice@example.org")
        val (bob, _) = instructorBrowser(admin, "bob@example.org")
        val exercise = alice.createExercise("Alice's drill", alice.fiveGasProfile().id)

        assertEquals(emptyList(), bob.get("/api/exercises").body<List<ExerciseDto>>())
        // Not 403: another instructor's exercise is reported as missing so its existence isn't revealed.
        assertEquals(HttpStatusCode.NotFound, bob.get("/api/exercises/${exercise.id}").status)
        assertEquals(HttpStatusCode.NotFound, bob.putJson("/api/exercises/${exercise.id}/criteria", CriteriaOverrides()).status)
        assertEquals(HttpStatusCode.NotFound, bob.patchJson("/api/exercises/${exercise.id}", mapOf("name" to "Mine now")).status)
        assertEquals(HttpStatusCode.NotFound, bob.delete("/api/exercises/${exercise.id}").status)

        assertEquals(listOf(exercise.id), alice.get("/api/exercises").body<List<ExerciseDto>>().map { it.id })
        assertEquals(listOf(exercise.id), admin.get("/api/exercises").body<List<ExerciseDto>>().map { it.id })
    }

    @Test
    fun addingAnInstructorGrantsAccess() = withApp {
        val admin = adminBrowser()
        val (alice, aliceDto) = instructorBrowser(admin, "alice@example.org")
        val (bob, bobDto) = instructorBrowser(admin, "bob@example.org")
        val exercise = alice.createExercise("Shared drill", alice.fiveGasProfile().id)

        val shared = alice.putJson(
            "/api/exercises/${exercise.id}/instructors",
            SetInstructorsRequest(listOf(aliceDto.id, bobDto.id)),
        )
        assertEquals(HttpStatusCode.OK, shared.status)
        assertEquals(HttpStatusCode.OK, bob.get("/api/exercises/${exercise.id}").status)

        val empty = alice.putJson("/api/exercises/${exercise.id}/instructors", SetInstructorsRequest(emptyList()))
        assertEquals(HttpStatusCode.UnprocessableEntity, empty.status)
    }

    @Test
    fun criteriaAreValidatedAndAppliedToTheEffectiveProfile() = withApp {
        val admin = adminBrowser()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)

        val bad = admin.putJson(
            "/api/exercises/${exercise.id}/criteria",
            CriteriaOverrides(mapOf("HCN" to ChannelOverride(lowAlarm = 8.0, highAlarm = 5.0))),
        )
        assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
        assertTrue(bad.body<ErrorResponse>().errors.any { it.channelId == "HCN" && it.field == "highAlarm" })

        val unknown = admin.putJson(
            "/api/exercises/${exercise.id}/criteria",
            CriteriaOverrides(mapOf("NH3" to ChannelOverride(lowAlarm = 25.0))),
        )
        assertEquals(HttpStatusCode.UnprocessableEntity, unknown.status)

        val good = admin.putJson(
            "/api/exercises/${exercise.id}/criteria",
            CriteriaOverrides(mapOf("HCN" to ChannelOverride(lowAlarm = 2.5, highAlarm = 5.0))),
        ).body<ExerciseDto>()
        assertEquals(exercise.version + 1, good.version)

        val effective = admin.get("/api/exercises/${exercise.id}/effective-profile").body<SensorProfile>()
        val hcn = effective.channel("HCN")!!
        assertEquals(2.5, hcn.lowAlarm)
        assertEquals(5.0, hcn.highAlarm)
        assertEquals(4.7, hcn.stelAlarm) // untouched fields keep the template value
    }

    @Test
    fun createValidatesNameProfileAndCriteria() = withApp {
        val admin = adminBrowser()
        val profileId = admin.fiveGasProfile().id

        val noName = admin.postJson("/api/exercises", mapOf("name" to " ", "profileId" to profileId))
        assertEquals(HttpStatusCode.UnprocessableEntity, noName.status)

        val unknownProfile = admin.postJson(
            "/api/exercises",
            mapOf("name" to "X", "profileId" to "00000000-0000-0000-0000-000000000000"),
        )
        assertEquals(HttpStatusCode.UnprocessableEntity, unknownProfile.status)

        val badId = admin.postJson("/api/exercises", mapOf("name" to "X", "profileId" to "not-a-uuid"))
        assertEquals(HttpStatusCode.BadRequest, badId.status)
    }

    @Test
    fun windIsValidated() = withApp {
        val admin = adminBrowser()
        val exercise = admin.createExercise("Drill", admin.fiveGasProfile().id)
        assertEquals(HttpStatusCode.UnprocessableEntity, admin.patchJson("/api/exercises/${exercise.id}", mapOf("windDirDeg" to 400.0)).status)
        val ok = admin.patchJson("/api/exercises/${exercise.id}", mapOf("windDirDeg" to 270.0)).body<ExerciseDto>()
        assertEquals(270.0, ok.windDirDeg)
    }
}
