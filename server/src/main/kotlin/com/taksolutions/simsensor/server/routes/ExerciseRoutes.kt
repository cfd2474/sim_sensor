package com.taksolutions.simsensor.server.routes

import com.taksolutions.simsensor.server.api.CreateExerciseRequest
import com.taksolutions.simsensor.server.api.SetInstructorsRequest
import com.taksolutions.simsensor.server.api.NotFoundException
import com.taksolutions.simsensor.server.api.UpdateExerciseRequest
import com.taksolutions.simsensor.server.api.toDto
import com.taksolutions.simsensor.server.auth.actor
import com.taksolutions.simsensor.server.exercises.ExerciseChanges
import com.taksolutions.simsensor.server.exercises.ExerciseService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Exercises. Mount inside `authenticate(AUTH_INSTRUCTOR)`. */
fun Route.exerciseRoutes(service: ExerciseService) {
    route("/api/exercises") {
        get {
            call.respond(service.list(call.actor()).map { it.toDto() })
        }

        post {
            val request = call.receive<CreateExerciseRequest>()
            val created = service.create(
                actor = call.actor(),
                name = request.name,
                profileId = parseUuid(request.profileId, "profileId"),
                o2Displacement = request.o2Displacement,
                location = request.location?.toLocation(),
            )
            call.respond(HttpStatusCode.Created, created.toDto())
        }

        route("/{id}") {
            get {
                call.respond(service.get(call.actor(), call.uuidParam("id")).toDto())
            }

            patch {
                val request = call.receive<UpdateExerciseRequest>()
                val changes = ExerciseChanges(
                    name = request.name,
                    profileId = request.profileId?.let { parseUuid(it, "profileId") },
                    o2Displacement = request.o2Displacement,
                    windDirDeg = request.windDirDeg,
                    windSpeedMps = request.windSpeedMps,
                    location = request.location?.toLocation(),
                )
                call.respond(service.update(call.actor(), call.uuidParam("id"), changes).toDto())
            }

            delete {
                service.delete(call.actor(), call.uuidParam("id"))
                call.respond(HttpStatusCode.NoContent)
            }

            put("/instructors") {
                val ids = call.receive<SetInstructorsRequest>().instructorIds.map { parseUuid(it, "instructorIds") }
                call.respond(service.setInstructors(call.actor(), call.uuidParam("id"), ids).toDto())
            }

            get("/monitors") {
                call.respond(service.monitors(call.actor(), call.uuidParam("id")).map { it.toDto() })
            }

            post("/{action}") {
                val action = ExerciseService.Action.entries.firstOrNull { it.name.equals(call.parameters["action"], ignoreCase = true) }
                    ?: throw NotFoundException("Unknown action")
                call.respond(service.transition(call.actor(), call.uuidParam("id"), action).toDto())
            }

            get("/events") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
                val before = call.request.queryParameters["before"]?.toLongOrNull()
                call.respond(service.events(call.actor(), call.uuidParam("id"), limit, before).map { it.toDto() })
            }
        }
    }
}
