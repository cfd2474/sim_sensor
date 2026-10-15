package com.taksolutions.simsensor.server.sources

import com.taksolutions.simsensor.profile.SensorProfile
import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.api.NotFoundException
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.events.EventLog
import com.taksolutions.simsensor.server.events.EventTypes
import com.taksolutions.simsensor.server.exercises.ExerciseRepository
import com.taksolutions.simsensor.server.exercises.ExerciseService
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.realtime.ChangeNotifier
import com.taksolutions.simsensor.source.Activation
import com.taksolutions.simsensor.source.GasSource
import com.taksolutions.simsensor.source.LatLon
import com.taksolutions.simsensor.source.SourceModel
import com.taksolutions.simsensor.source.SourceValidator
import java.time.Clock
import java.util.UUID
import kotlin.math.max

/** A concentration grid for the instructor's map overlay. Row 0 is the northern edge. */
@kotlinx.serialization.Serializable
data class PreviewGrid(
    val gas: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val rows: Int,
    val cols: Int,
    val values: List<Double>,
    val max: Double,
    /** Exercise time the grid is for; null for the full-strength planning view. */
    val atS: Double?,
)

/**
 * Virtual leaks of an exercise (docs/DESIGN.md §8.1). Every change raises the exercise version
 * so phones get the new snapshot within about a second.
 */
class SourceService(
    private val sources: SourceRepository,
    private val exercises: ExerciseRepository,
    private val exerciseService: ExerciseService,
    private val notifier: ChangeNotifier,
    private val eventLog: EventLog,
    private val clock: Clock,
    private val monitors: com.taksolutions.simsensor.server.profiles.ExerciseMonitors,
    private val beacons: com.taksolutions.simsensor.server.beacons.BeaconService,
) {
    suspend fun list(actor: InstructorPrincipal, exerciseId: UUID): List<GasSource> {
        exerciseService.get(actor, exerciseId)
        return sources.list(exerciseId)
    }

    suspend fun create(actor: InstructorPrincipal, exerciseId: UUID, source: GasSource): GasSource {
        validate(actor, exerciseId, source)
        val created = sources.create(exerciseId, source)
        changed(exerciseId, actor, EventTypes.SOURCE_CREATED, created)
        return created
    }

    suspend fun update(actor: InstructorPrincipal, exerciseId: UUID, id: UUID, source: GasSource): GasSource {
        requireInExercise(id, exerciseId)
        validate(actor, exerciseId, source)
        val updated = sources.update(id, source) ?: throw NotFoundException("Leak not found")
        changed(exerciseId, actor, EventTypes.SOURCE_UPDATED, updated)
        return updated
    }

    suspend fun delete(actor: InstructorPrincipal, exerciseId: UUID, id: UUID) {
        exerciseService.get(actor, exerciseId)
        val existing = requireInExercise(id, exerciseId)
        sources.delete(id)
        changed(exerciseId, actor, EventTypes.SOURCE_DELETED, existing)
    }

    /** Starts a MANUAL leak now (exercise time). Only while the exercise runs or is paused. */
    suspend fun activate(actor: InstructorPrincipal, exerciseId: UUID, id: UUID): GasSource {
        val exercise = exerciseService.get(actor, exerciseId)
        val source = requireInExercise(id, exerciseId)
        if (source.timing.activation != Activation.MANUAL) throw ConflictException("Only manual leaks are activated by hand")
        if (!exercise.sourcesEmit) throw ConflictException("Start the exercise first")
        val activated = sources.activate(id, exercise.elapsedS(clock.instant())) ?: throw NotFoundException("Leak not found")
        changed(exerciseId, actor, EventTypes.SOURCE_ACTIVATED, activated)
        return activated
    }

    /**
     * Concentration of [gas] over a box, from the same model the phones use. While the
     * exercise runs (or is paused) the grid shows the current time; otherwise every enabled
     * leak at full strength, for planning.
     */
    suspend fun preview(
        actor: InstructorPrincipal,
        exerciseId: UUID,
        gas: String,
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        cells: Int,
    ): PreviewGrid {
        val exercise = exerciseService.get(actor, exerciseId)
        val profile = exerciseService.combinedMonitor(actor, exerciseId)
        if (profile.channel(gas) == null) invalid("gas", "No monitor in this exercise has '$gas'")
        if (!(south < north && west < east) || south < -90 || north > 90 || west < -180 || east > 180) invalid("bounds", "Invalid map area")
        val size = cells.coerceIn(8, 160)
        val (rows, cols) = gridShape(south, west, north, east, size)

        val live = exercise.sourcesEmit
        val atS = if (live) exercise.elapsedS(clock.instant()) else null
        val list = sources.list(exerciseId)
        val values = ArrayList<Double>(rows * cols)
        var max = 0.0
        for (r in 0 until rows) {
            val lat = north - (r + 0.5) * (north - south) / rows
            for (c in 0 until cols) {
                val lon = west + (c + 0.5) * (east - west) / cols
                val here = LatLon(lat, lon)
                val v = SourceModel.contributions(
                    list, here, atS ?: 0.0, emitting = true,
                    exerciseWindFromDeg = exercise.windDirDeg, o2Displacement = exercise.o2Displacement,
                    profile = profile, ignoreTiming = !live,
                    // Beacon leaks are drawn around their map pins.
                    beaconDistances = SourceModel.anchorDistances(list, here),
                )[gas] ?: 0.0
                values += v
                max = max(max, v)
            }
        }
        return PreviewGrid(gas, south, west, north, east, rows, cols, values, max, atS)
    }

    /** Keep cells roughly square on the ground; the longer side gets [size] cells. */
    private fun gridShape(south: Double, west: Double, north: Double, east: Double, size: Int): Pair<Int, Int> {
        val heightM = (north - south) * 111_195.0
        val widthM = (east - west) * 111_195.0 * kotlin.math.cos(Math.toRadians((north + south) / 2))
        return if (heightM >= widthM) {
            size to (size * widthM / heightM).toInt().coerceAtLeast(1)
        } else {
            (size * heightM / widthM).toInt().coerceAtLeast(1) to size
        }
    }

    private suspend fun validate(actor: InstructorPrincipal, exerciseId: UUID, source: GasSource) {
        exerciseService.get(actor, exerciseId)
        // A leak describes the hazard, not the meters: any gas a monitor knows is allowed. Phones
        // whose monitor lacks the gas simply don't show it, like the real device.
        val profile: SensorProfile = monitors.allGases()
        val errors = SourceValidator.validate(source, profile)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        val shape = source.shape
        if (shape is com.taksolutions.simsensor.source.BeaconShape) {
            beacons.requireExists(shape.beaconId)
            val exercise = exerciseService.get(actor, exerciseId)
            if (exercise.sourcesEmit) beacons.ensureFree(exerciseId, listOf(source))
        }
    }

    private suspend fun requireInExercise(id: UUID, exerciseId: UUID): GasSource {
        val stored = sources.find(id)
        if (stored == null || stored.exerciseId != exerciseId) throw NotFoundException("Leak not found")
        return stored.source
    }

    private suspend fun changed(exerciseId: UUID, actor: InstructorPrincipal, type: String, source: GasSource) {
        exercises.bumpVersion(exerciseId)
        notifier.exerciseChanged(exerciseId)
        eventLog.record(
            type, exerciseId = exerciseId, actorId = actor.id,
            data = mapOf(
                "name" to source.name,
                "shape" to source.shape::class.simpleName?.removeSuffix("Shape")?.lowercase(),
                "gases" to source.gases.joinToString { "${it.gas} ${it.peak}" },
                "enabled" to source.enabled.toString(),
            ),
        )
    }

    private fun invalid(field: String, message: String): Nothing =
        throw ValidationException(listOf(ValidationError(null, field, message)))
}
