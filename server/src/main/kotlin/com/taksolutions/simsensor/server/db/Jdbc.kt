package com.taksolutions.simsensor.server.db

import kotlinx.serialization.json.Json
import org.postgresql.util.PGobject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** JSON used for jsonb columns. Tolerates fields added by newer versions. */
val DbJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Marks a parameter to be bound as jsonb. */
class JsonbParam(val json: String)

fun Connection.execute(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { statement ->
        statement.bind(params)
        statement.executeUpdate()
    }

/** Runs [sql] once per parameter row in a single JDBC batch. */
fun Connection.executeBatch(sql: String, rows: List<Array<out Any?>>) {
    if (rows.isEmpty()) return
    prepareStatement(sql).use { statement ->
        rows.forEach { row ->
            statement.bind(row)
            statement.addBatch()
        }
        statement.executeBatch()
    }
}

fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(params)
        statement.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    query(sql, *params, map = map).firstOrNull()

private fun PreparedStatement.bind(params: Array<out Any?>) {
    params.forEachIndexed { i, value ->
        val index = i + 1
        when (value) {
            null -> setNull(index, Types.OTHER)
            is Instant -> setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
            is JsonbParam -> setObject(index, PGobject().apply { type = "jsonb"; this.value = value.json })
            else -> setObject(index, value)
        }
    }
}

fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

fun ResultSet.doubleOrNull(column: String): Double? = getDouble(column).takeUnless { wasNull() }

fun ResultSet.uuidList(column: String): List<UUID> =
    (getArray(column)?.array as? Array<*>)?.map { it as UUID } ?: emptyList()

/** Postgres unique_violation. */
fun SQLException.isUniqueViolation(): Boolean = sqlState == "23505"
