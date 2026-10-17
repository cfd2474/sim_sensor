package com.taksolutions.simsensor.server.geo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** One place an address search found. */
@Serializable
data class GeocodeResult(val label: String, val lat: Double, val lon: Double)

/** Turns an address into places (exercise location, chunk 4.5). */
interface Geocoder {
    /** @throws GeocoderException when the service can't be reached or answers with an error */
    suspend fun search(query: String): List<GeocodeResult>
}

class GeocoderException(message: String) : Exception(message)

/**
 * OpenStreetMap Nominatim search (usage policy read 2026-10-16: identify the application, at most
 * one request per second, cache results, no autocomplete). Use it behind [CachingGeocoder].
 * [baseUrl] is configurable (`SIM_GEOCODER_URL`) so the service can be switched.
 */
class NominatimGeocoder(
    private val baseUrl: String,
    private val userAgent: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : Geocoder {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String): List<GeocodeResult> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, Charsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/search?format=jsonv2&limit=5&q=$q"))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw GeocoderException("The address search service can't be reached")
        }
        if (response.statusCode() != 200) throw GeocoderException("The address search service answered ${response.statusCode()}")
        json.parseToJsonElement(response.body()).jsonArray.mapNotNull { item ->
            val o: JsonObject = item.jsonObject
            val label = o["display_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val lat = o["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = o["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            GeocodeResult(label, lat, lon)
        }
    }
}

/**
 * Caches answers ([ttl], up to [maxEntries] queries) and spaces requests to the service at least
 * [minInterval] apart across the whole server, as the Nominatim policy asks.
 */
class CachingGeocoder(
    private val inner: Geocoder,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofDays(1),
    private val maxEntries: Int = 500,
    private val minInterval: Duration = Duration.ofSeconds(1),
) : Geocoder {
    private val cache = object : LinkedHashMap<String, Pair<Instant, List<GeocodeResult>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Instant, List<GeocodeResult>>>?) = size > maxEntries
    }
    private val lock = Mutex()
    private var lastRequest: Instant = Instant.EPOCH

    override suspend fun search(query: String): List<GeocodeResult> = lock.withLock {
        val key = query.trim().lowercase().replace(Regex("\\s+"), " ")
        cache[key]?.let { (at, results) -> if (Duration.between(at, clock.instant()) < ttl) return@withLock results }
        val wait = Duration.between(clock.instant(), lastRequest.plus(minInterval))
        if (!wait.isNegative && !wait.isZero) delay(wait.toMillis())
        lastRequest = clock.instant()
        inner.search(query.trim()).also { cache[key] = clock.instant() to it }
    }
}
