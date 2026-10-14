package com.taksolutions.simsensor.server.events

import com.taksolutions.simsensor.server.db.Database
import com.taksolutions.simsensor.server.db.DbJson
import com.taksolutions.simsensor.server.db.JsonbParam
import com.taksolutions.simsensor.server.db.execute
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

/** Event types written to `event_log`, for after-action review. */
object EventTypes {
    const val DEVICE_CONNECTED = "device.connected"
    const val DEVICE_DISCONNECTED = "device.disconnected"
    const val DEVICE_ENROLLED = "device.enrolled"
    const val DEVICE_ASSIGNED = "device.assigned"
    const val DEVICE_REVOKED = "device.revoked"
    const val DEVICE_EVENT = "device.event"
    const val OVERRIDE_SET = "override.set"
    const val OVERRIDE_RELEASED = "override.released"
    const val COMMAND_SENT = "command.sent"
    const val CRITERIA_CHANGED = "exercise.criteria"
}

interface EventLog {
    suspend fun record(
        type: String,
        exerciseId: UUID? = null,
        deviceId: UUID? = null,
        actorId: UUID? = null,
        data: Map<String, String?> = emptyMap(),
        ts: Instant? = null,
    )
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
}
