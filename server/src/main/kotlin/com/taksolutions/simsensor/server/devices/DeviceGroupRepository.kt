package com.taksolutions.simsensor.server.devices

import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.db.Database
import com.taksolutions.simsensor.server.db.execute
import com.taksolutions.simsensor.server.db.isUniqueViolation
import com.taksolutions.simsensor.server.db.query
import com.taksolutions.simsensor.server.db.queryOne
import com.taksolutions.simsensor.server.db.uuid
import java.sql.SQLException
import java.util.UUID

data class DeviceGroup(val id: UUID, val name: String)

interface DeviceGroupRepository {
    suspend fun list(): List<DeviceGroup>
    suspend fun find(id: UUID): DeviceGroup?

    /** Throws [ConflictException] when the name is taken. */
    suspend fun create(name: String): DeviceGroup
    suspend fun delete(id: UUID): Boolean
}

class JdbcDeviceGroupRepository(private val db: Database) : DeviceGroupRepository {

    override suspend fun list(): List<DeviceGroup> = db.transaction {
        query("SELECT id, name FROM device_groups ORDER BY lower(name)") { DeviceGroup(it.uuid("id"), it.getString("name")) }
    }

    override suspend fun find(id: UUID): DeviceGroup? = db.transaction {
        queryOne("SELECT id, name FROM device_groups WHERE id = ?", id) { DeviceGroup(it.uuid("id"), it.getString("name")) }
    }

    override suspend fun create(name: String): DeviceGroup =
        try {
            db.transaction {
                queryOne("INSERT INTO device_groups (name) VALUES (?) RETURNING id, name", name.trim()) {
                    DeviceGroup(it.uuid("id"), it.getString("name"))
                }!!
            }
        } catch (e: SQLException) {
            if (e.isUniqueViolation()) throw ConflictException("A group with that name already exists") else throw e
        }

    override suspend fun delete(id: UUID): Boolean = db.transaction {
        execute("DELETE FROM device_groups WHERE id = ?", id) > 0
    }
}
