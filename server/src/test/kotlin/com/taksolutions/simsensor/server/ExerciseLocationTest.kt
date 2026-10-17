package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.server.api.CreateExerciseRequest
import com.taksolutions.simsensor.server.api.ExerciseDto
import com.taksolutions.simsensor.server.api.LocationDto
import com.taksolutions.simsensor.server.api.UpdateExerciseRequest
import com.taksolutions.simsensor.server.geo.CachingGeocoder
import com.taksolutions.simsensor.server.geo.GeocodeResult
import com.taksolutions.simsensor.server.geo.Geocoder
import com.taksolutions.simsensor.server.support.adminBrowser
import com.taksolutions.simsensor.server.support.browser
import com.taksolutions.simsensor.server.support.fiveGasProfile
import com.taksolutions.simsensor.server.support.patchJson
import com.taksolutions.simsensor.server.support.postJson
import com.taksolutions.simsensor.server.support.withApp
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Chunk 4.5: where an exercise takes place, and the address search behind it. */
class ExerciseLocationTest {
    private val depot = LocationDto("Fire Training Center, 1 Main St", 33.6239, -117.1269)

    @Test
    fun anExerciseRemembersWhereItTakesPlace() = withApp {
        val admin = adminBrowser()
        val profileId = admin.fiveGasProfile().id
        val created = admin.postJson("/api/exercises", CreateExerciseRequest("Rail yard", profileId, location = depot)).body<ExerciseDto>()
        assertEquals(depot, created.location)
        val moved = admin.patchJson("/api/exercises/${created.id}", UpdateExerciseRequest(location = depot.copy(label = "Yard 2", lat = 33.7)))
            .body<ExerciseDto>()
        assertEquals(LocationDto("Yard 2", 33.7, depot.lon), moved.location)
        assertEquals(null, admin.postJson("/api/exercises", CreateExerciseRequest("No place", profileId)).body<ExerciseDto>().location)
        val bad = admin.postJson("/api/exercises", CreateExerciseRequest("Bad", profileId, location = depot.copy(lat = 95.0)))
        assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
    }

    @Test
    fun addressSearchGoesThroughTheServer() = withApp(geocoder = FixedGeocoder(listOf(GeocodeResult("1 Main St, Springfield", 39.8, -89.6)))) {
        val admin = adminBrowser()
        val found = admin.get("/api/geocode?q=1%20Main%20St").body<List<GeocodeResult>>()
        assertEquals("1 Main St, Springfield", found.single().label)
        assertEquals(HttpStatusCode.UnprocessableEntity, admin.get("/api/geocode?q=a").status)
        assertEquals(HttpStatusCode.Unauthorized, browser().get("/api/geocode?q=1%20Main%20St").status)
    }

    @Test
    fun answersAreCachedAndRequestsSpacedOut() = runBlocking {
        val inner = FixedGeocoder(listOf(GeocodeResult("Somewhere", 1.0, 2.0)))
        val geocoder = CachingGeocoder(inner, minInterval = Duration.ofMillis(300))
        geocoder.search("1 Main St")
        geocoder.search("  1  main st ")
        assertEquals(1, inner.calls, "the same address (any spacing or case) is answered from the cache")
        val start = System.nanoTime()
        geocoder.search("2 Main St")
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 250, "a new request waits for the minimum interval")
        assertEquals(2, inner.calls)
    }

    private class FixedGeocoder(private val results: List<GeocodeResult>) : Geocoder {
        var calls = 0
        override suspend fun search(query: String): List<GeocodeResult> {
            calls++
            return results
        }
    }
}
