package com.taksolutions.simsensor.server.settings

import com.taksolutions.simsensor.protocol.Units
import com.taksolutions.simsensor.server.db.Database
import com.taksolutions.simsensor.server.db.DbJson
import com.taksolutions.simsensor.server.db.JsonbParam
import com.taksolutions.simsensor.server.db.execute
import com.taksolutions.simsensor.server.db.query
import com.taksolutions.simsensor.server.events.EventLog
import com.taksolutions.simsensor.server.events.EventTypes
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.instructors.requireAdmin
import com.taksolutions.simsensor.server.realtime.ChangeNotifier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** System-wide settings (admin). Storage is always metric; these only change display. */
@Serializable
data class SystemSettings(val units: Units = Units.IMPERIAL)

interface SettingsRepository {
    suspend fun all(): Map<String, JsonElement>
    suspend fun put(key: String, value: JsonElement, by: UUID)
}

class JdbcSettingsRepository(private val db: Database) : SettingsRepository {
    override suspend fun all(): Map<String, JsonElement> = db.transaction {
        query("SELECT key, value FROM system_settings") {
            it.getString("key") to DbJson.parseToJsonElement(it.getString("value"))
        }.toMap()
    }

    override suspend fun put(key: String, value: JsonElement, by: UUID) {
        db.transaction {
            execute(
                """
                INSERT INTO system_settings (key, value, updated_by) VALUES (?, ?, ?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by, updated_at = now()
                """.trimIndent(),
                key, JsonbParam(value.toString()), by,
            )
        }
    }
}

class SettingsService(
    private val settings: SettingsRepository,
    private val notifier: ChangeNotifier,
    private val eventLog: EventLog,
) {
    suspend fun get(): SystemSettings {
        val all = settings.all()
        val units = all[UNITS]?.jsonPrimitive?.contentOrNull?.let { raw -> Units.entries.firstOrNull { it.name == raw } }
        return SystemSettings(units = units ?: Units.IMPERIAL)
    }

    suspend fun update(actor: InstructorPrincipal, changes: SystemSettings): SystemSettings {
        requireAdmin(actor)
        val before = get()
        if (changes.units != before.units) {
            settings.put(UNITS, JsonPrimitive(changes.units.name), actor.id)
            eventLog.record(EventTypes.SETTINGS_CHANGED, actorId = actor.id, data = mapOf("units" to changes.units.name))
            notifier.settingsChanged()
        }
        return get()
    }

    private companion object {
        const val UNITS = "units"
    }
}
