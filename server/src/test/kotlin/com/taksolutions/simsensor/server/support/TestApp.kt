package com.taksolutions.simsensor.server.support

import com.taksolutions.simsensor.server.AppServices
import com.taksolutions.simsensor.server.BootstrapAdmin
import com.taksolutions.simsensor.server.ServerConfig
import com.taksolutions.simsensor.server.api.ExerciseDto
import com.taksolutions.simsensor.server.api.InstructorDto
import com.taksolutions.simsensor.server.api.ProfileDto
import com.taksolutions.simsensor.server.auth.Argon2PasswordHasher
import com.taksolutions.simsensor.server.module
import com.taksolutions.simsensor.server.realtime.InMemoryBus
import com.taksolutions.simsensor.server.realtime.RealtimeBus
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.time.Clock
import kotlin.test.assertEquals

const val ADMIN_EMAIL = "admin@example.org"
const val ADMIN_PASSWORD = "admin-password-123"
const val TEST_PASSWORD = "instructor-pass-123"

val TEST_CONFIG = ServerConfig(version = "1.2.3", cookieSecure = false)

/**
 * A running test server on an empty, seeded database (built-in profiles + bootstrap admin).
 * Uses the in-memory bus unless [bus] is given.
 */
fun withApp(
    clock: Clock = Clock.systemUTC(),
    bus: RealtimeBus = InMemoryBus(),
    mail: com.taksolutions.simsensor.server.mail.MailSender = RecordingMailSender(),
    /** Null = a fresh install without SIM_ADMIN_* (gets the default admin). */
    bootstrapAdmin: BootstrapAdmin? = BootstrapAdmin(ADMIN_EMAIL, ADMIN_PASSWORD),
    /** Tests never call the real address search. */
    geocoder: com.taksolutions.simsensor.server.geo.Geocoder? = null,
    block: suspend ApplicationTestBuilder.(AppServices) -> Unit,
) = testApplication {
    TestDatabase.reset()
    // Cheap Argon2 parameters keep tests fast; production uses the defaults.
    val services = AppServices.create(
        TestDatabase.dataSource,
        TEST_CONFIG,
        hasher = Argon2PasswordHasher(memoryKib = 64, iterations = 1),
        clock = clock,
        bus = bus,
        mailSender = mail,
        geocoder = geocoder,
    )
    services.seeder.seed(bootstrapAdmin)
    application { module(TEST_CONFIG, services) }
    block(services)
}

/** A client with its own cookie jar, like a separate browser. */
fun ApplicationTestBuilder.browser(): HttpClient = createClient {
    install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
    install(HttpCookies)
    install(WebSockets)
}

suspend inline fun <reified T> HttpClient.sendJson(method: HttpMethod, path: String, body: T): HttpResponse =
    request(path) {
        this.method = method
        contentType(ContentType.Application.Json)
        setBody(body)
    }

suspend inline fun <reified T> HttpClient.postJson(path: String, body: T) = sendJson(HttpMethod.Post, path, body)

suspend inline fun <reified T> HttpClient.putJson(path: String, body: T) = sendJson(HttpMethod.Put, path, body)

suspend inline fun <reified T> HttpClient.patchJson(path: String, body: T) = sendJson(HttpMethod.Patch, path, body)

suspend fun HttpClient.login(email: String, password: String): HttpResponse =
    postJson("/api/auth/login", mapOf("email" to email, "password" to password))

/** A browser logged in as the bootstrap admin. */
suspend fun ApplicationTestBuilder.adminBrowser(): HttpClient = browser().also {
    assertEquals(HttpStatusCode.OK, it.login(ADMIN_EMAIL, ADMIN_PASSWORD).status)
}

/** Creates an instructor (as [admin]) and returns a browser logged in as them. */
suspend fun ApplicationTestBuilder.instructorBrowser(admin: HttpClient, email: String): Pair<HttpClient, InstructorDto> {
    val response = admin.postJson(
        "/api/instructors",
        mapOf("email" to email, "name" to email.substringBefore('@'), "password" to TEST_PASSWORD, "role" to "instructor"),
    )
    assertEquals(HttpStatusCode.Created, response.status)
    val client = browser()
    assertEquals(HttpStatusCode.OK, client.login(email, TEST_PASSWORD).status)
    return client to response.body()
}

suspend fun HttpClient.fiveGasProfile(): ProfileDto =
    get("/api/profiles").body<List<ProfileDto>>().first { it.definition.name == "5-Gas" }

suspend fun HttpClient.createExercise(name: String, profileId: String): ExerciseDto {
    val response = postJson("/api/exercises", mapOf("name" to name, "profileId" to profileId))
    assertEquals(HttpStatusCode.Created, response.status)
    return response.body()
}

/** Records email instead of sending it; [failWith] makes every send fail. */
class RecordingMailSender(var failWith: String? = null) : com.taksolutions.simsensor.server.mail.MailSender {
    val sent = java.util.concurrent.CopyOnWriteArrayList<com.taksolutions.simsensor.server.mail.MailMessage>()

    override suspend fun send(settings: com.taksolutions.simsensor.server.mail.SmtpSettings, message: com.taksolutions.simsensor.server.mail.MailMessage) {
        failWith?.let { throw IllegalStateException(it) }
        sent += message
    }

    /** The token in the last email's link. */
    fun lastToken(): String = Regex("token=([A-Za-z0-9_-]+)").find(sent.last().text)!!.groupValues[1]
}
