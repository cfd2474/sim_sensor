package com.taksolutions.simsensor.server.routes

import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.server.api.ErrorResponse
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.geo.Geocoder
import com.taksolutions.simsensor.server.geo.GeocoderException
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /api/geocode?q=` (instructors): places for an exercise address. Goes through the server
 * so results are cached and the service's usage policy is kept (chunk 4.5). Mount inside
 * `authenticate(AUTH_INSTRUCTOR)`.
 */
fun Route.geocodeRoutes(geocoder: Geocoder?) {
    get("/api/geocode") {
        val q = call.request.queryParameters["q"]?.trim().orEmpty()
        if (q.length !in 3..200) throw ValidationException(listOf(ValidationError(null, "q", "Type an address of 3–200 characters")))
        if (geocoder == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("geocoder_off", "Address search is switched off on this server"))
            return@get
        }
        try {
            call.respond(geocoder.search(q))
        } catch (e: GeocoderException) {
            call.respond(HttpStatusCode.BadGateway, ErrorResponse("geocoder_failed", e.message ?: "Address search failed"))
        }
    }
}
