package com.taksolutions.simsensor.loadtest

import com.taksolutions.simsensor.protocol.EnrollRequest
import com.taksolutions.simsensor.protocol.EnrollResponse
import com.taksolutions.simsensor.protocol.Hello
import com.taksolutions.simsensor.protocol.OverridesUpdate
import com.taksolutions.simsensor.protocol.ProtocolCodec
import com.taksolutions.simsensor.protocol.Telemetry
import com.taksolutions.simsensor.protocol.TelemetrySample
import com.taksolutions.simsensor.protocol.Welcome
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Load test for a Sim Sensor server (chunk 1.5).
 *
 *   simsensor-loadtest --url https://host --devices 500 --duration 120
 *
 * Admin credentials come from SIM_LT_EMAIL / SIM_LT_PASSWORD. The run creates a fleet token
 * and a test exercise, enrolls and connects N simulated phones (1 Hz telemetry), applies a few
 * overrides to every phone and measures how long each takes to arrive, then revokes and deletes
 * everything it created.
 */
fun main(args: Array<String>) = runBlocking {
    val options = Options.parse(args)
    val email = System.getenv("SIM_LT_EMAIL") ?: fail("Set SIM_LT_EMAIL")
    val password = System.getenv("SIM_LT_PASSWORD") ?: fail("Set SIM_LT_PASSWORD")
    LoadTest(options).run(email, password)
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}

data class Options(val url: String, val devices: Int, val durationSeconds: Int, val connectPerSecond: Int, val overrides: Int) {
    companion object {
        fun parse(args: Array<String>): Options {
            val map = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
            return Options(
                url = map["url"]?.trimEnd('/') ?: fail("--url is required"),
                devices = map["devices"]?.toInt() ?: 100,
                durationSeconds = map["duration"]?.toInt() ?: 60,
                connectPerSecond = map["connect-rate"]?.toInt() ?: 50,
                overrides = map["overrides"]?.toInt() ?: 3,
            )
        }
    }
}

@Serializable private data class Login(val email: String, val password: String)
@Serializable private data class TokenRequest(val kind: String, val label: String)
@Serializable private data class Created(val token: String, val info: Id)
@Serializable private data class Id(val id: String)
@Serializable private data class Profile(val id: String)
@Serializable private data class ExerciseRequest(val name: String, val profileId: String)
@Serializable private data class Assign(val exerciseId: String?)
@Serializable private data class Target(val all: Boolean)
@Serializable private data class OverrideRequest(val target: Target, val channelId: String?, val mode: String, val value: Double)

class LoadTest(private val options: Options) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    /** REST calls: a small pool of kept-alive connections (the run makes thousands of calls). */
    private val http = HttpClient(CIO) {
        engine {
            endpoint.maxConnectionsPerRoute = 32
            endpoint.keepAliveTime = 60_000
            endpoint.connectTimeout = 15_000
        }
        install(ContentNegotiation) { json(json) }
        install(HttpCookies)
    }

    /** Phone sockets: one long-lived connection each. */
    private val sockets = HttpClient(CIO) {
        engine {
            maxConnectionsCount = 100_000
            endpoint.maxConnectionsPerRoute = 100_000
            endpoint.connectTimeout = 15_000
        }
        install(WebSockets) { pingIntervalMillis = 15_000 }
    }

    // Measurements.
    private val welcomeMillis = ConcurrentHashMap.newKeySet<Long>()
    private val connected = AtomicInteger()
    private val failedConnects = AtomicInteger()
    private val dropped = AtomicInteger()
    private val samplesSent = AtomicLong()
    /** override value -> arrival latencies (ms) */
    private val overrideLatency = ConcurrentHashMap<Double, MutableList<Long>>()
    private val overrideSentAt = ConcurrentHashMap<Double, Long>()

    suspend fun run(email: String, password: String) {
        val base = options.url
        println("Load test: ${options.devices} phones for ${options.durationSeconds} s against $base")
        check(http.post("$base/api/auth/login") { jsonBody(Login(email, password)) }.status.isSuccess()) { "admin login failed" }

        val fleet = http.post("$base/api/enrollment-tokens") { jsonBody(TokenRequest("fleet", "load test")) }.body<Created>()
        val profileId = http.get("$base/api/profiles").body<List<Profile>>().first().id
        val exerciseId = http.post("$base/api/exercises") { jsonBody(ExerciseRequest("Load test ${Instant.now()}", profileId)) }.body<Id>().id
        http.post("$base/api/exercises/$exerciseId/start")

        val devices = mutableListOf<EnrollResponse>()
        try {
            val start = System.nanoTime()
            devices += enrollAll(base, fleet.token)
            println("Enrolled ${devices.size} in ${ms(start)} ms")
            val assignStart = System.nanoTime()
            parallel(devices, 20) { http.put("$base/api/devices/${it.deviceId}/exercise") { jsonBody(Assign(exerciseId)) } }
            println("Assigned in ${ms(assignStart)} ms")

            coroutineScope {
                val sessions = launch(Dispatchers.IO) { connectAll(base, devices) }
                // Wait until every phone is connected (or gave up), then measure override fan-out.
                val connectStart = System.currentTimeMillis()
                while (connected.get() + failedConnects.get() < devices.size && System.currentTimeMillis() - connectStart < 120_000) delay(250)
                delay(3_000)
                val connectSeconds = ((System.currentTimeMillis() - connectStart) / 1000).toInt()
                println("Connected ${connected.get()} / ${devices.size} (failed ${failedConnects.get()}) in $connectSeconds s")
                repeat(options.overrides) { i ->
                    val value = 100.0 + i
                    overrideSentAt[value] = System.currentTimeMillis()
                    http.post("$base/api/exercises/$exerciseId/overrides") { jsonBody(OverrideRequest(Target(true), "CO", "FIXED", value)) }
                    delay(5_000)
                }
                val remaining = options.durationSeconds - connectSeconds - options.overrides * 5
                if (remaining > 0) delay(remaining * 1000L)
                sessions.cancel()
            }
        } finally {
            report()
            sockets.close()
            cleanup(base, exerciseId, fleet.info.id, devices)
            http.close()
        }
    }

    private suspend fun enrollAll(base: String, token: String): List<EnrollResponse> =
        parallel((1..options.devices).toList(), 20) { n ->
            http.post("$base/api/devices/enroll") {
                jsonBody(EnrollRequest(token, appVersion = "loadtest", deviceName = "LT-%04d".format(n), model = "loadtest"))
            }.body<EnrollResponse>()
        }

    private suspend fun connectAll(base: String, devices: List<EnrollResponse>) = coroutineScope {
        val wsUrl = base.replaceFirst("http", "ws") + "/ws/device"
        val gap = 1000L / options.connectPerSecond.coerceAtLeast(1)
        devices.forEach { device ->
            launch { simulate(wsUrl, device) }
            delay(gap)
        }
    }

    /** One phone: hello, welcome, then 1 Hz telemetry while recording override arrivals. */
    private suspend fun simulate(wsUrl: String, device: EnrollResponse) {
        val began = System.currentTimeMillis()
        var welcomed = false
        try {
            sockets.webSocket(wsUrl, request = { bearerAuth(device.deviceToken) }) {
                send(Frame.Text(ProtocolCodec.encode(Hello(appVersion = "loadtest", model = "loadtest"))))
                val welcome = withTimeoutOrNull(15_000) {
                    var w: Welcome? = null
                    while (w == null) w = ProtocolCodec.decodeOrNull((incoming.receive() as Frame.Text).readText()) as? Welcome
                    w
                } ?: run { failedConnects.incrementAndGet(); return@webSocket }
                welcomeMillis += System.currentTimeMillis() - began
                connected.incrementAndGet()
                welcomed = true

                val lat = 32.7 + Random.nextDouble(0.01)
                val lon = -117.16 + Random.nextDouble(0.01)
                val sender = launch {
                    delay(Random.nextLong(1000)) // spread phones across the second
                    while (isActive) {
                        val sample = TelemetrySample(
                            ts = System.currentTimeMillis() + (welcome.serverTimeMillis - began),
                            lat = lat, lon = lon, accuracyM = 5f, battery = 0.9f,
                            readings = mapOf("O2" to 20.9, "LEL" to 0.0, "CO" to 0.0, "H2S" to 0.0, "HCN" to 0.0),
                        )
                        send(Frame.Text(ProtocolCodec.encode(Telemetry(listOf(sample)))))
                        samplesSent.incrementAndGet()
                        delay(1000)
                    }
                }
                try {
                    for (frame in incoming) {
                        val message = ProtocolCodec.decodeOrNull((frame as? Frame.Text)?.readText() ?: continue)
                        if (message is OverridesUpdate) {
                            val value = message.overrides.firstOrNull()?.value ?: continue
                            val sent = overrideSentAt[value] ?: continue
                            overrideLatency.computeIfAbsent(value) { java.util.Collections.synchronizedList(mutableListOf()) } +=
                                System.currentTimeMillis() - sent
                        }
                    }
                } finally {
                    sender.cancel()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (welcomed) dropped.incrementAndGet() else failedConnects.incrementAndGet()
        }
    }

    private fun report() {
        println()
        println("=== Results ===")
        println("Phones connected: ${connected.get()} / ${options.devices}, failed connects: ${failedConnects.get()}, dropped: ${dropped.get()}")
        println("Welcome after connect (ms): ${stats(welcomeMillis.toList())}")
        println("Telemetry samples sent: ${samplesSent.get()}")
        overrideSentAt.keys.sorted().forEach { value ->
            val latencies = overrideLatency[value].orEmpty().toList()
            println("Override CO=$value reached ${latencies.size} / ${connected.get()} phones, latency ms: ${stats(latencies)}")
        }
    }

    private suspend fun cleanup(base: String, exerciseId: String, tokenId: String, devices: List<EnrollResponse>) {
        println("Cleaning up ${devices.size} phones…")
        parallel(devices, 20) {
            http.post("$base/api/devices/${it.deviceId}/revoke")
            http.delete("$base/api/devices/${it.deviceId}")
        }
        http.post("$base/api/exercises/$exerciseId/end")
        http.delete("$base/api/exercises/$exerciseId")
        http.delete("$base/api/enrollment-tokens/$tokenId")
        http.post("$base/api/auth/logout")
        println("Cleanup done.")
    }

    private suspend fun <T, R> parallel(items: List<T>, concurrency: Int, block: suspend (T) -> R): List<R> = coroutineScope {
        val permits = Semaphore(concurrency)
        items.map { item -> async { permits.withPermit { block(item) } } }.awaitAll()
    }

    private inline fun <reified T> io.ktor.client.request.HttpRequestBuilder.jsonBody(body: T) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun ms(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    private fun stats(values: List<Long>): String {
        if (values.isEmpty()) return "n/a"
        val sorted = values.sorted()
        fun p(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
        return "p50 ${p(0.5)}, p95 ${p(0.95)}, p99 ${p(0.99)}, max ${sorted.last()}"
    }
}
