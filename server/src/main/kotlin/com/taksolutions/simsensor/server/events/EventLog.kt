package com.taksolutions.simsensor.server.events

import com.taksolutions.simsensor.server.db.Database
import com.taksolutions.simsensor.server.db.DbJson
import com.taksolutions.simsensor.server.db.JsonbParam
import com.taksolutions.simsensor.server.db.execute
import com.taksolutions.simsensor.server.db.executeBatch
import com.taksolutions.simsensor.server.db.instant
import com.taksolutions.simsensor.server.db.query
import com.taksolutions.simsensor.server.db.uuidOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID

/** Event types written to `event_log`, for after-action review. */
object EventTypes {
    const val DEVICE_CONNECTED = "device.connected"
    const val DEVICE_DISCONNECTED = "device.disconnected"
    const val DEVICE_ENROLLED = "device.enrolled"
    const val DEVICE_REENROLLED = "device.reenrolled"
    const val SERVER_UPDATE_REQUESTED = "server.update_requested"
    const val SERVER_BACKUP_REQUESTED = "server.backup_requested"
    const val SERVER_RESTORE_REQUESTED = "server.restore_requested"
    const val SERVER_BACKUP_RETENTION_CHANGED = "server.backup_retention"
    const val DEVICE_ASSIGNED = "device.assigned"
    const val DEVICE_MONITOR = "device.monitor"
    const val DEVICE_REVOKED = "device.revoked"
    const val DEVICE_EVENT = "device.event"
    const val OVERRIDE_SET = "override.set"
    const val OVERRIDE_RELEASED = "override.released"
    const val COMMAND_SENT = "command.sent"
    /** Retired with per-exercise criteria (v0.11.0); kept so old log entries keep their label. */
    const val CRITERIA_CHANGED = "exercise.criteria"
    const val EXERCISE_STATE = "exercise.state"
    const val SOURCE_CREATED = "source.created"
    const val SOURCE_UPDATED = "source.updated"
    const val SOURCE_DELETED = "source.deleted"
    const val SOURCE_ACTIVATED = "source.activated"
    const val SETTINGS_CHANGED = "settings.changed"
    const val BEACON_ADDED = "beacon.added"
    const val ACCOUNT_INVITED = "account.invited"
    const val PASSWORD_RESET = "account.password_reset"
    const val BEACON_REMOVED = "beacon.removed"
    const val BEACON_CALIBRATED = "beacon.calibrated"
    const val BEACON_EDGE = "beacon.edge"
}

/** One stored event, with the names of the device and instructor involved. */
data class LoggedEvent(
    val id: Long,
    val ts: Instant,
    val type: String,
    val deviceId: UUID?,
    val deviceName: String?,
    val actorId: UUID?,
    val actorName: String?,
    val data: Map<String, String?>,
)

interface EventLog {
    suspend fun record(
        type: String,
        exerciseId: UUID? = null,
        deviceId: UUID? = null,
        actorId: UUID? = null,
        data: Map<String, String?> = emptyMap(),
        ts: Instant? = null,
    )

    /** The same event for many devices, in one transaction. */
    suspend fun recordForDevices(
        type: String,
        exerciseId: UUID?,
        deviceIds: Collection<UUID>,
        actorId: UUID?,
        data: Map<String, String?> = emptyMap(),
    )

    /** Newest first; [beforeId] pages back through older events. */
    suspend fun forExercise(exerciseId: UUID, limit: Int, beforeId: Long? = null): List<LoggedEvent>
}

class JdbcEventLog(private val db: Database) : EventLog {
    override suspend fun record(
        type: String,
        exerciseId: UUID?,
        deviceId: UUID?,
        actorId: UUID?,
        data: Map<String, String?>,
        ts: Instant?,
    ) {
        val json = JsonObject(data.mapValues { JsonPrimitive(it.value) })
        db.transaction {
            execute(
                "INSERT INTO event_log (type, exercise_id, device_id, actor_id, data, ts) VALUES (?, ?, ?, ?, ?, COALESCE(?, now()))",
                type, exerciseId, deviceId, actorId, JsonbParam(DbJson.encodeToString(JsonObject.serializer(), json)), ts,
            )
        }
    }

    override suspend fun recordForDevices(
        type: String,
        exerciseId: UUID?,
        deviceIds: Collection<UUID>,
        actorId: UUID?,
        data: Map<String, String?>,
    ) {
        if (deviceIds.isEmpty()) return
        val json = JsonbParam(DbJson.encodeToString(JsonObject.serializer(), JsonObject(data.mapValues { JsonPrimitive(it.value) })))
        db.transaction {
            executeBatch(
                "INSERT INTO event_log (type, exercise_id, device_id, actor_id, data) VALUES (?, ?, ?, ?, ?)",
                deviceIds.map { arrayOf<Any?>(type, exerciseId, it, actorId, json) },
            )
        }
    }

    override suspend fun forExercise(exerciseId: UUID, limit: Int, beforeId: Long?): List<LoggedEvent> = db.transaction {
        query(
            """
            SELECT e.id, e.ts, e.type, e.device_id, d.name AS device_name, e.actor_id, i.name AS actor_name, e.data
              FROM event_log e
              LEFT JOIN devices d ON d.id = e.device_id
              LEFT JOIN instructors i ON i.id = e.actor_id
             WHERE e.exercise_id = ? AND (CAST(? AS bigint) IS NULL OR e.id < ?)
             ORDER BY e.id DESC
             LIMIT ?
            """.trimIndent(),
            exerciseId, beforeId, beforeId, limit,
        ) { rs ->
            LoggedEvent(
                id = rs.getLong("id"),
                ts = rs.instant("ts"),
                type = rs.getString("type"),
                deviceId = rs.uuidOrNull("device_id"),
                deviceName = rs.getString("device_name"),
                actorId = rs.uuidOrNull("actor_id"),
                actorName = rs.getString("actor_name"),
                data = DbJson.decodeFromString(JsonObject.serializer(), rs.getString("data"))
                    .mapValues { it.value.jsonPrimitive.contentOrNull },
            )
        }
    }
}
