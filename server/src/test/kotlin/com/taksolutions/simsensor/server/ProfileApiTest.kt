package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.profile.DefaultProfiles
import com.taksolutions.simsensor.server.api.ErrorResponse
import com.taksolutions.simsensor.server.api.ExerciseDto
import com.taksolutions.simsensor.server.api.ProfileDto
import com.taksolutions.simsensor.server.support.adminBrowser
import com.taksolutions.simsensor.server.support.createExercise
import com.taksolutions.simsensor.server.support.fiveGasProfile
import com.taksolutions.simsensor.server.support.instructorBrowser
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

class ProfileApiTest {

    @Test
    fun fiveGasTemplateIsSeeded() = withApp {
        val profile = adminBrowser().fiveGasProfile()
        assertTrue(profile.builtin)
        assertEquals(listOf("O2", "LEL", "CO", "H2S", "HCN"), profile.definition.channels.map { it.id })
    }

    @Test
    fun instructorsCanReadButNotChangeProfiles() = withApp {
        val (instructor, _) = instructorBrowser(adminBrowser(), "a@example.org")
        assertEquals(HttpStatusCode.OK, instructor.get("/api/profiles").status)
        val create = instructor.postJson("/api/profiles", DefaultProfiles.FIVE_GAS.copy(name = "Mine"))
        assertEquals(HttpStatusCode.Forbidden, create.status)
    }

    @Test
    fun adminCreatesAValidProfileAndInvalidOnesAreRejected() = withApp {
        val admin = adminBrowser()
        assertEquals(HttpStatusCode.Created, admin.postJson("/api/profiles", DefaultProfiles.FIVE_GAS.copy(name = "Copy")).status)

        val broken = DefaultProfiles.FIVE_GAS.copy(
            name = "Broken",
            channels = DefaultProfiles.FIVE_GAS.channels.map { if (it.id == "CO") it.copy(highAlarm = 10.0) else it },
        )
        val response = admin.postJson("/api/profiles", broken)
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue(response.body<ErrorResponse>().errors.any { it.channelId == "CO" && it.field == "highAlarm" })

        assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/profiles", DefaultProfiles.FIVE_GAS.copy(name = "copy")).status)
    }

    @Test
    fun updatingAProfileRaisesItsVersionAndItsExercisesVersions() = withApp {
        val admin = adminBrowser()
        val profile = admin.fiveGasProfile()
        val exercise = admin.createExercise("Drill", profile.id)

        val renamed = profile.definition.copy(name = "5-Gas agency default")
        val updated = admin.putJson("/api/profiles/${profile.id}", renamed).body<ProfileDto>()
        assertEquals(profile.version + 1, updated.version)

        val after = admin.get("/api/exercises/${exercise.id}").body<ExerciseDto>()
        assertEquals(exercise.version + 1, after.version)
    }


    @Test
    fun builtInAndInUseProfilesCannotBeDeleted() = withApp {
        val admin = adminBrowser()
        val builtin = admin.fiveGasProfile()
        assertEquals(HttpStatusCode.Conflict, admin.delete("/api/profiles/${builtin.id}").status)

        val custom = admin.postJson("/api/profiles", DefaultProfiles.FIVE_GAS.copy(name = "Custom")).body<ProfileDto>()
        val exercise = admin.createExercise("Uses custom", custom.id)
        assertEquals(HttpStatusCode.Conflict, admin.delete("/api/profiles/${custom.id}").status)

        admin.delete("/api/exercises/${exercise.id}")
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/profiles/${custom.id}").status)
    }
}
