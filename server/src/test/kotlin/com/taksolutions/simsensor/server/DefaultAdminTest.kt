package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.server.api.CreateInstructorRequest
import com.taksolutions.simsensor.server.api.InstructorDto
import com.taksolutions.simsensor.server.api.LoginRequest
import com.taksolutions.simsensor.server.api.MeResponse
import com.taksolutions.simsensor.server.api.UpdateInstructorRequest
import com.taksolutions.simsensor.server.bootstrap.DefaultAdmin
import com.taksolutions.simsensor.server.support.adminBrowser
import com.taksolutions.simsensor.server.support.browser
import com.taksolutions.simsensor.server.support.patchJson
import com.taksolutions.simsensor.server.support.postJson
import com.taksolutions.simsensor.server.support.withApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Chunk 4.4: the default admin of a fresh install, and the console warning about it. */
class DefaultAdminTest {
    @Test
    fun aFreshInstallStartsWithTheDefaultAdminUntilItIsDeactivated() = withApp(bootstrapAdmin = null) {
        val default = browser()
        val login = default.postJson("/api/auth/login", LoginRequest(DefaultAdmin.EMAIL, DefaultAdmin.PASSWORD))
        assertEquals(HttpStatusCode.OK, login.status, "the README's credentials work on a fresh install")
        assertTrue(login.body<MeResponse>().defaultAdminActive)

        // The installer's own admin, then the default one deactivated by them.
        val created = default.postJson(
            "/api/instructors",
            CreateInstructorRequest(email = "chief@agency.example", name = "Chief", password = "own-password-1", role = "admin"),
        ).body<InstructorDto>()
        val own = browser()
        assertEquals(HttpStatusCode.OK, own.postJson("/api/auth/login", LoginRequest("chief@agency.example", "own-password-1")).status)
        assertTrue(own.get("/api/auth/me").body<MeResponse>().defaultAdminActive, "warned until it's deactivated")
        val defaultId = own.get("/api/instructors").body<List<InstructorDto>>().single { it.email == DefaultAdmin.EMAIL }.id
        assertEquals(HttpStatusCode.OK, own.patchJson("/api/instructors/$defaultId", UpdateInstructorRequest(disabled = true)).status)
        assertFalse(own.get("/api/auth/me").body<MeResponse>().defaultAdminActive)
        assertEquals(HttpStatusCode.Unauthorized, browser().postJson("/api/auth/login", LoginRequest(DefaultAdmin.EMAIL, DefaultAdmin.PASSWORD)).status)
        assertTrue(created.id.isNotEmpty())
    }

    @Test
    fun installsWithAnAdminFromTheEnvironmentHaveNoDefaultAccount() = withApp {
        val admin = adminBrowser()
        assertFalse(admin.get("/api/auth/me").body<MeResponse>().defaultAdminActive)
        assertEquals(HttpStatusCode.Unauthorized, browser().postJson("/api/auth/login", LoginRequest(DefaultAdmin.EMAIL, DefaultAdmin.PASSWORD)).status)
    }
}
