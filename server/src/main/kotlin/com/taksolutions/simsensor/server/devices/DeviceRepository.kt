package com.taksolutions.simsensor.server.devices

import com.taksolutions.simsensor.protocol.OverrideMode
import com.taksolutions.simsensor.server.db.Database
import com.taksolutions.simsensor.server.db.doubleOrNull
import com.taksolutions.simsensor.server.db.execute
import com.taksolutions.simsensor.server.db.instant
import com.taksolutions.simsensor.server.db.query
import com.taksolutions.simsensor.server.db.queryOne
import com.taksolutions.simsensor.server.db.uuid
import com.taksolutions.simsensor.server.db.uuidOrNull
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

data class Device(
    val id: UUID,
    val name: String,
    val model: String?,
    val appVersion: String?,
    val groupId: UUID?,
    val exerciseId: UUID?,
    val enrolledAt: Instant,
    val revokedAt: Instant?,
    val lastSeenAt: Instant?,
    val overridesVersion: Int,
) {
    val revoked: Boolean get() = revokedAt != null
}

/** An active override as stored; `channelId` null means all channels. */
data class StoredOverride(
    val deviceId: UUID,
    val channelId: String?,
    val exerciseId: UUID,
    val mode: OverrideMode,
    val value: Double?,
    val rampSeconds: Double?,
    val createdAt: Instant,
)

/** A new override for several devices at once. */
data class OverrideSpec(
    val channelId: String?,
    val mode: OverrideMode,
    val value: Double?,
    val rampSeconds: Double?,
)

interface DeviceRepository {
    suspend fun list(): List<Device>
    suspend fun find(id: UUID): Device?

    /** The device owning [tokenHash], unless revoked. */
    suspend fun findActiveByTokenHash(tokenHash: String): Device?
    suspend fun listInExercise(exerciseId: UUID): List<Device>
    suspend fun recordHello(id: UUID, appVersion: String, model: String?, now: Instant)
    suspend fun touchLastSeen(ids: Collection<UUID>, now: Instant)
    suspend fun rename(id: UUID, name: String): Device?
    suspend fun setGroup(id: UUID, groupId: UUID?): Device?

    /** Moves the device to [exerciseId] (or none), clearing its overrides. */
    suspend fun assign(id: UUID, exerciseId: UUID?): Device?
    suspend fun revoke(id: UUID, now: Instant): Device?

    suspend fun overrides(deviceId: UUID): List<StoredOverride>

    /** Sets (replaces) the override for [spec]'s channel on each device. */
    suspend fun setOverride(deviceIds: Collection<UUID>, exerciseId: UUID, spec: OverrideSpec, createdBy: UUID)

    /** Removes the override for [channelId] (or every override when null) on each device. */
    suspend fun releaseOverrides(deviceIds: Collection<UUID>, channelId: String?)
}

class JdbcDeviceRepository(private val db: Database) : DeviceRepository {

    override suspend fun list(): List<Device> = db.transaction {
        query("SELECT * FROM devices ORDER BY lower(name)", map = ::toDevice)
    }

    override suspend fun find(id: UUID): Device? = db.transaction { findIn(this, id) }

    override suspend fun findActiveByTokenHash(tokenHash: String): Device? = db.transaction {
        queryOne("SELECT * FROM devices WHERE token_hash = ? AND revoked_at IS NULL", tokenHash, map = ::toDevice)
    }

    override suspend fun listInExercise(exerciseId: UUID): List<Device> = db.transaction {
        query("SELECT * FROM devices WHERE exercise_id = ? ORDER BY lower(name)", exerciseId, map = ::toDevice)
    }

    override suspend fun recordHello(id: UUID, appVersion: String, model: String?, now: Instant) {
        db.transaction {
            execute(
                "UPDATE devices SET app_version = ?, model = COALESCE(?, model), last_seen_at = ? WHERE id = ?",
                appVersion, model, now, id,
            )
        }
    }

    override suspend fun touchLastSeen(ids: Collection<UUID>, now: Instant) {
        if (ids.isEmpty()) return
        db.transaction {
            execute("UPDATE devices SET last_seen_at = ? WHERE id = ANY(?)", now, createArrayOf("uuid", ids.toTypedArray()))
        }
    }

    override suspend fun rename(id: UUID, name: String): Device? = db.transaction {
        queryOne("UPDATE devices SET name = ? WHERE id = ? RETURNING *", name.trim(), id, map = ::toDevice)
    }

    override suspend fun setGroup(id: UUID, groupId: UUID?): Device? = db.transaction {
        queryOne("UPDATE devices SET group_id = ? WHERE id = ? RETURNING *", groupId, id, map = ::toDevice)
    }

    override suspend fun assign(id: UUID, exerciseId: UUID?): Device? = db.transaction {
        execute("DELETE FROM device_overrides WHERE device_id = ?", id)
        queryOne(
            "UPDATE devices SET exercise_id = ?, overrides_version = overrides_version + 1 WHERE id = ? RETURNING *",
            exerciseId, id,
            map = ::toDevice,
        )
    }

    override suspend fun revoke(id: UUID, now: Instant): Device? = db.transaction {
        execute("DELETE FROM device_overrides WHERE device_id = ?", id)
        queryOne(
            "UPDATE devices SET revoked_at = ?, token_hash = NULL, exercise_id = NULL WHERE id = ? RETURNING *",
            now, id,
            map = ::toDevice,
        )
    }

    override suspend fun overrides(deviceId: UUID): List<StoredOverride> = db.transaction {
        query("SELECT * FROM device_overrides WHERE device_id = ? ORDER BY channel_id", deviceId, map = ::toOverride)
    }

    override suspend fun setOverride(deviceIds: Collection<UUID>, exerciseId: UUID, spec: OverrideSpec, createdBy: UUID) {
        db.transaction {
            deviceIds.forEach { deviceId ->
                execute(
                    """
                    INSERT INTO device_overrides (device_id, channel_id, exercise_id, mode, value, ramp_seconds, created_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (device_id, channel_id) DO UPDATE
                       SET exercise_id = EXCLUDED.exercise_id, mode = EXCLUDED.mode, value = EXCLUDED.value,
                           ramp_seconds = EXCLUDED.ramp_seconds, created_by = EXCLUDED.created_by, created_at = now()
                    """.trimIndent(),
                    deviceId, spec.channelId ?: ALL_CHANNELS, exerciseId, spec.mode.name.lowercase(),
                    spec.value, spec.rampSeconds, createdBy,
                )
            }
            bumpOverridesVersion(this, deviceIds)
        }
    }

    override suspend fun releaseOverrides(deviceIds: Collection<UUID>, channelId: String?) {
        db.transaction {
            val ids = createArrayOf("uuid", deviceIds.toTypedArray())
            if (channelId == null) {
                execute("DELETE FROM device_overrides WHERE device_id = ANY(?)", ids)
            } else {
                execute("DELETE FROM device_overrides WHERE device_id = ANY(?) AND channel_id = ?", ids, channelId)
            }
            bumpOverridesVersion(this, deviceIds)
        }
    }

    private fun bumpOverridesVersion(connection: Connection, deviceIds: Collection<UUID>) {
        connection.execute(
            "UPDATE devices SET overrides_version = overrides_version + 1 WHERE id = ANY(?)",
            connection.createArrayOf("uuid", deviceIds.toTypedArray()),
        )
    }

    private fun findIn(connection: Connection, id: UUID): Device? =
        connection.queryOne("SELECT * FROM devices WHERE id = ?", id, map = ::toDevice)

    private fun toOverride(rs: ResultSet) = StoredOverride(
        deviceId = rs.uuid("device_id"),
        channelId = rs.getString("channel_id").takeUnless { it == ALL_CHANNELS },
        exerciseId = rs.uuid("exercise_id"),
        mode = OverrideMode.valueOf(rs.getString("mode").uppercase()),
        value = rs.doubleOrNull("value"),
        rampSeconds = rs.doubleOrNull("ramp_seconds"),
        createdAt = rs.instant("created_at"),
    )

    private companion object {
        const val ALL_CHANNELS = "*"
    }
}

fun toDevice(rs: ResultSet) = Device(
    id = rs.uuid("id"),
    name = rs.getString("name"),
    model = rs.getString("model"),
    appVersion = rs.getString("app_version"),
    groupId = rs.uuidOrNull("group_id"),
    exerciseId = rs.uuidOrNull("exercise_id"),
    enrolledAt = rs.instant("enrolled_at"),
    revokedAt = rs.getObject("revoked_at", OffsetDateTime::class.java)?.toInstant(),
    lastSeenAt = rs.getObject("last_seen_at", OffsetDateTime::class.java)?.toInstant(),
    overridesVersion = rs.getInt("overrides_version"),
)
