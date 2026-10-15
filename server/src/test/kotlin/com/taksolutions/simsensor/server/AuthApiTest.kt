package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.server.api.MeResponse
import com.taksolutions.simsensor.server.support.ADMIN_EMAIL
import com.taksolutions.simsensor.server.support.ADMIN_PASSWORD
import com.taksolutions.simsensor.server.support.TEST_PASSWORD
import com.taksolutions.simsensor.server.support.adminBrowser
import com.taksolutions.simsensor.server.support.browser
import com.taksolutions.simsensor.server.support.instructorBrowser
import com.taksolutions.simsensor.server.support.login
import com.taksolutions.simsensor.server.support.patchJson
import com.taksolutions.simsensor.server.support.postJson
import com.taksolutions.simsensor.server.support.withApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthApiTest {

    @Test
    fun loginThenMe() = withApp {
        val browser = browser()
        assertEquals(HttpStatusCode.OK, browser.login(ADMIN_EMAIL, ADMIN_PASSWORD).status)
        val me = browser.get("/api/auth/me").body<MeResponse>()
        assertEquals(ADMIN_EMAIL, me.email)
        assertEquals("admin", me.role)
    }

    @Test
    fun emailIsCaseInsensitive() = withApp {
        assertEquals(HttpStatusCode.OK, browser().login(ADMIN_EMAIL.uppercase(), ADMIN_PASSWORD).status)
    }

    @Test
    fun wrongPasswordIsRejected() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, browser().login(ADMIN_EMAIL, "not-the-password").status)
    }

    @Test
    fun protectedRoutesNeedLogin() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/auth/me").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/exercises").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/profiles").status)
    }

    @Test
    fun logoutEndsTheSession() = withApp {
        val browser = adminBrowser()
        assertEquals(HttpStatusCode.NoContent, browser.post("/api/auth/logout").status)
        assertEquals(HttpStatusCode.Unauthorized, browser.get("/api/auth/me").status)
    }

    @Test
    fun repeatedFailuresAreBlockedEvenWithTheRightPassword() = withApp {
        val browser = browser()
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, browser.login(ADMIN_EMAIL, "wrong-password-$it").status) }
        assertEquals(HttpStatusCode.TooManyRequests, browser.login(ADMIN_EMAIL, ADMIN_PASSWORD).status)
    }

    @Test
    fun disablingAnInstructorEndsTheirSessionsAndBlocksLogin() = withApp {
        val admin = adminBrowser()
        val (instructor, dto) = instructorBrowser(admin, "a@example.org")
        assertEquals(HttpStatusCode.OK, admin.patchJson("/api/instructors/${dto.id}", mapOf("disabled" to true)).status)
        assertEquals(HttpStatusCode.Unauthorized, instructor.get("/api/auth/me").status)
        assertEquals(HttpStatusCode.Unauthorized, browser().login("a@example.org", TEST_PASSWORD).status)
    }

    @Test
    fun adminCannotDisableThemselves() = withApp {
        val admin = adminBrowser()
        val me = admin.get("/api/auth/me").body<MeResponse>()
        assertEquals(HttpStatusCode.Conflict, admin.patchJson("/api/instructors/${me.id}", mapOf("disabled" to true)).status)
    }

    @Test
    fun instructorsCanListButNotManageInstructors() = withApp {
        val (instructor, me) = instructorBrowser(adminBrowser(), "a@example.org")
        assertEquals(HttpStatusCode.OK, instructor.get("/api/instructors").status)
        assertEquals(HttpStatusCode.Forbidden, instructor.patchJson("/api/instructors/${me.id}", mapOf("name" to "Me")).status)
        val create = instructor.postJson(
            "/api/instructors",
            mapOf("email" to "b@example.org", "name" to "B", "password" to TEST_PASSWORD),
        )
        assertEquals(HttpStatusCode.Forbidden, create.status)
    }

    @Test
    fun shortPasswordsAndDuplicateEmailsAreRejected() = withApp {
        val admin = adminBrowser()
        val short = admin.postJson("/api/instructors", mapOf("email" to "c@example.org", "name" to "C", "password" to "seven77"))
        assertEquals(HttpStatusCode.UnprocessableEntity, short.status, "7 characters is too short")
        val eight = admin.postJson("/api/instructors", mapOf("email" to "d@example.org", "name" to "D", "password" to "abcdefgh"))
        assertEquals(HttpStatusCode.Created, eight.status, "8 characters, no other rules")
        val duplicate = admin.postJson(
            "/api/instructors",
            mapOf("email" to ADMIN_EMAIL.uppercase(), "name" to "Dup", "password" to TEST_PASSWORD),
        )
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
    }

    @Test
    fun changingOwnPasswordSignsOut() = withApp {
        val admin = adminBrowser()
        val change = admin.postJson(
            "/api/auth/password",
            mapOf("currentPassword" to ADMIN_PASSWORD, "newPassword" to "a-brand-new-password"),
        )
        assertEquals(HttpStatusCode.NoContent, change.status)
        assertEquals(HttpStatusCode.Unauthorized, admin.get("/api/auth/me").status)
        assertEquals(HttpStatusCode.OK, browser().login(ADMIN_EMAIL, "a-brand-new-password").status)
    }
}
