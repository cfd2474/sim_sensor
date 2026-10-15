package com.taksolutions.simsensor.server.exercises

import com.taksolutions.simsensor.profile.CriteriaOverrides
import com.taksolutions.simsensor.server.db.Database
import com.taksolutions.simsensor.server.db.DbJson
import com.taksolutions.simsensor.server.db.JsonbParam
import com.taksolutions.simsensor.server.db.doubleOrNull
import com.taksolutions.simsensor.server.db.execute
import com.taksolutions.simsensor.server.db.instant
import com.taksolutions.simsensor.server.db.query
import com.taksolutions.simsensor.server.db.queryOne
import com.taksolutions.simsensor.server.db.uuid
import com.taksolutions.simsensor.server.db.uuidList
import com.taksolutions.simsensor.server.db.uuidOrNull
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

enum class ExerciseState(val dbValue: String) {
    DRAFT("draft"),
    RUNNING("running"),
    PAUSED("paused"),
    ENDED("ended"),
    ARCHIVED("archived"),
    ;

    companion object {
        fun fromDb(value: String): ExerciseState = entries.first { it.dbValue == value }
    }
}

data class Exercise(
    val id: UUID,
    val name: String,
    val profileId: UUID,
    val criteria: CriteriaOverrides,
    val state: ExerciseState,
    val windDirDeg: Double?,
    val windSpeedMps: Double?,
    val o2Displacement: Boolean,
    /** Raised on every change that devices must receive. */
    val version: Int,
    val instructorIds: List<UUID>,
    val createdBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Run time accumulated before [clockResumedAt] (seconds). */
    val clockElapsedS: Double = 0.0,
    /** When the clock last started running; null while stopped or paused. */
    val clockResumedAt: Instant? = null,
) {
    /** Sources emit while the exercise runs or is paused (paused freezes their timing). */
    val sourcesEmit: Boolean get() = state == ExerciseState.RUNNING || state == ExerciseState.PAUSED

    /** Exercise time at [now]: frozen while paused, advancing while running. */
    fun elapsedS(now: Instant): Double =
        clockElapsedS + (clockResumedAt?.let { (now.toEpochMilli() - it.toEpochMilli()).coerceAtLeast(0) / 1000.0 } ?: 0.0)
}

/** What a lifecycle step does to the exercise clock. */
enum class ClockChange {
    /** Start from zero and run; manual source activations are cleared. */
    RESTART,

    /** Stop advancing, keep the elapsed time. */
    FREEZE,

    /** Continue advancing from the frozen time. */
    RESUME,

    /** Back to zero, stopped; manual source activations are cleared. */
    ZERO,
}

/** Fields of an exercise that can be edited; null means unchanged. */
data class ExerciseChanges(
    val name: String? = null,
    val profileId: UUID? = null,
    val o2Displacement: Boolean? = null,
    val windDirDeg: Double? = null,
    val windSpeedMps: Double? = null,
)

interface ExerciseRepository {
    suspend fun listAll(): List<Exercise>
    suspend fun listForInstructor(instructorId: UUID): List<Exercise>
    suspend fun listUsingProfile(profileId: UUID): List<Exercise>
    suspend fun find(id: UUID): Exercise?

    /** Creates the exercise with [createdBy] as its first instructor. */
    suspend fun create(name: String, profileId: UUID, criteria: CriteriaOverrides, o2Displacement: Boolean, createdBy: UUID): Exercise
    suspend fun update(id: UUID, changes: ExerciseChanges): Exercise?
    suspend fun setCriteria(id: UUID, criteria: CriteriaOverrides): Exercise?
    suspend fun setInstructors(id: UUID, instructorIds: Collection<UUID>): Exercise?
    suspend fun delete(id: UUID): Boolean

    /**
     * Moves the exercise to [state] (raising its version) and, in the same transaction, opens
     * a session ([openSession]) or closes the open one ([closeSession]).
     */
    suspend fun transition(
        id: UUID,
        state: ExerciseState,
        openSession: Boolean,
        closeSession: Boolean,
        clock: ClockChange,
        now: Instant,
    ): Exercise?

    /** Raises the version so phones reload the exercise (e.g. a source changed). */
    suspend fun bumpVersion(id: UUID)

    /** The running session of each exercise that has one. */
    suspend fun openSessions(): Map<UUID, UUID>
}

class JdbcExerciseRepository(private val db: Database) : ExerciseRepository {

    override suspend fun listAll(): List<Exercise> = db.transaction {
        query("$SELECT GROUP BY e.id ORDER BY e.created_at DESC", map = ::toExercise)
    }

    override suspend fun listForInstructor(instructorId: UUID): List<Exercise> = db.transaction {
        query(
            "$SELECT WHERE e.id IN (SELECT exercise_id FROM exercise_instructors WHERE instructor_id = ?) " +
                "GROUP BY e.id ORDER BY e.created_at DESC",
            instructorId,
            map = ::toExercise,
        )
    }

    override suspend fun listUsingProfile(profileId: UUID): List<Exercise> = db.transaction {
        query("$SELECT WHERE e.profile_id = ? GROUP BY e.id", profileId, map = ::toExercise)
    }

    override suspend fun find(id: UUID): Exercise? = db.transaction { findIn(this, id) }

    override suspend fun create(
        name: String,
        profileId: UUID,
        criteria: CriteriaOverrides,
        o2Displacement: Boolean,
        createdBy: UUID,
    ): Exercise = db.transaction {
        val id = queryOne(
            """
            INSERT INTO exercises (name, profile_id, criteria_overrides, o2_displacement, created_by)
            VALUES (?, ?, ?, ?, ?) RETURNING id
            """.trimIndent(),
            name.trim(), profileId, json(criteria), o2Displacement, createdBy,
        ) { it.uuid("id") }!!
        execute("INSERT INTO exercise_instructors (exercise_id, instructor_id) VALUES (?, ?)", id, createdBy)
        findIn(this, id)!!
    }

    override suspend fun update(id: UUID, changes: ExerciseChanges): Exercise? = db.transaction {
        val count = execute(
            """
            UPDATE exercises
               SET name = COALESCE(?, name),
                   profile_id = COALESCE(?, profile_id),
                   o2_displacement = COALESCE(?, o2_displacement),
                   wind_dir_deg = COALESCE(?, wind_dir_deg),
                   wind_speed_mps = COALESCE(?, wind_speed_mps),
                   version = version + 1,
                   updated_at = now()
             WHERE id = ?
            """.trimIndent(),
            changes.name?.trim(), changes.profileId, changes.o2Displacement, changes.windDirDeg, changes.windSpeedMps, id,
        )
        if (count == 0) null else findIn(this, id)
    }

    override suspend fun setCriteria(id: UUID, criteria: CriteriaOverrides): Exercise? = db.transaction {
        val count = execute(
            "UPDATE exercises SET criteria_overrides = ?, version = version + 1, updated_at = now() WHERE id = ?",
            json(criteria), id,
        )
        if (count == 0) null else findIn(this, id)
    }

    override suspend fun setInstructors(id: UUID, instructorIds: Collection<UUID>): Exercise? = db.transaction {
        if (findIn(this, id) == null) return@transaction null
        execute("DELETE FROM exercise_instructors WHERE exercise_id = ?", id)
        instructorIds.toSet().forEach { instructorId ->
            execute("INSERT INTO exercise_instructors (exercise_id, instructor_id) VALUES (?, ?)", id, instructorId)
        }
        findIn(this, id)
    }

    override suspend fun transition(
        id: UUID,
        state: ExerciseState,
        openSession: Boolean,
        closeSession: Boolean,
        clock: ClockChange,
        now: Instant,
    ): Exercise? = db.transaction {
        val current = findIn(this, id) ?: return@transaction null
        val (elapsed, resumedAt) = when (clock) {
            ClockChange.RESTART -> 0.0 to now
            ClockChange.FREEZE -> current.elapsedS(now) to null
            ClockChange.RESUME -> current.clockElapsedS to now
            ClockChange.ZERO -> 0.0 to null
        }
        execute(
            """
            UPDATE exercises SET state = ?, clock_elapsed_s = ?, clock_resumed_at = ?, version = version + 1, updated_at = now()
             WHERE id = ?
            """.trimIndent(),
            state.dbValue, elapsed, resumedAt, id,
        )
        if (clock == ClockChange.RESTART || clock == ClockChange.ZERO) {
            execute("UPDATE gas_sources SET activated_at_s = NULL WHERE exercise_id = ?", id)
        }
        if (closeSession) execute("UPDATE exercise_sessions SET ended_at = now() WHERE exercise_id = ? AND ended_at IS NULL", id)
        if (openSession) execute("INSERT INTO exercise_sessions (exercise_id) VALUES (?)", id)
        findIn(this, id)
    }

    override suspend fun bumpVersion(id: UUID) {
        db.transaction { execute("UPDATE exercises SET version = version + 1, updated_at = now() WHERE id = ?", id) }
    }

    override suspend fun openSessions(): Map<UUID, UUID> = db.transaction {
        query("SELECT exercise_id, id FROM exercise_sessions WHERE ended_at IS NULL") { it.uuid("exercise_id") to it.uuid("id") }.toMap()
    }

    override suspend fun delete(id: UUID): Boolean = db.transaction {
        execute("DELETE FROM exercises WHERE id = ?", id) > 0
    }

    private fun findIn(connection: Connection, id: UUID): Exercise? =
        connection.queryOne("$SELECT WHERE e.id = ? GROUP BY e.id", id, map = ::toExercise)

    private fun json(criteria: CriteriaOverrides) =
        JsonbParam(DbJson.encodeToString(CriteriaOverrides.serializer(), criteria))

    private fun toExercise(rs: ResultSet) = Exercise(
        id = rs.uuid("id"),
        name = rs.getString("name"),
        profileId = rs.uuid("profile_id"),
        criteria = DbJson.decodeFromString(CriteriaOverrides.serializer(), rs.getString("criteria_overrides")),
        state = ExerciseState.fromDb(rs.getString("state")),
        windDirDeg = rs.doubleOrNull("wind_dir_deg"),
        windSpeedMps = rs.doubleOrNull("wind_speed_mps"),
        o2Displacement = rs.getBoolean("o2_displacement"),
        version = rs.getInt("version"),
        instructorIds = rs.uuidList("instructor_ids"),
        createdBy = rs.uuidOrNull("created_by"),
        createdAt = rs.instant("created_at"),
        updatedAt = rs.instant("updated_at"),
        clockElapsedS = rs.getDouble("clock_elapsed_s"),
        clockResumedAt = rs.getObject("clock_resumed_at", java.time.OffsetDateTime::class.java)?.toInstant(),
    )

    private companion object {
        /** Exercise columns plus the ids of its instructors; callers append WHERE / GROUP BY. */
        const val SELECT = """
            SELECT e.*,
                   COALESCE(array_agg(ei.instructor_id) FILTER (WHERE ei.instructor_id IS NOT NULL), '{}') AS instructor_ids
              FROM exercises e
              LEFT JOIN exercise_instructors ei ON ei.exercise_id = e.id
        """
    }
}
