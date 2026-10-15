package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.server.support.TestDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * V4 on data written before it: the meter-wide `alarmLatching` becomes per-alarm flags, and the
 * built-in template's untouched response times become 5 s. The migration already ran on the
 * empty test database, so the test inserts old-format rows and runs its SQL again.
 */
class LatchingMigrationTest {

    @BeforeTest
    fun reset() = TestDatabase.reset()

    private val v4 = javaClass.getResource("/db/migration/V4__per_alarm_latching.sql")!!.readText()

    private fun channel(id: String, t90: Double) =
        """{"id":"$id","label":"$id","unit":"PPM","rangeMin":0,"rangeMax":100,"resolution":1,"baseline":0,"lowAlarm":10,"highAlarm":20,"t90Seconds":$t90}"""

    private fun query(sql: String): String = TestDatabase.dataSource.connection.use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) } }
    }

    private fun execute(sql: String) = TestDatabase.dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

    private fun definition(name: String): JsonObject =
        Json.parseToJsonElement(query("SELECT definition::text FROM sensor_profiles WHERE name = '$name'")).jsonObject

    @Test
    fun meterWideLatchingBecomesPerAlarm() {
        execute(
            """
            INSERT INTO sensor_profiles (name, builtin, definition) VALUES
              ('Builtin', true,  '{"name":"Builtin","alarmLatching":true,"channels":[${channel("CO", 30.0)},${channel("H2S", 12.0)}]}'),
              ('Custom',  false, '{"name":"Custom","alarmLatching":false,"channels":[${channel("CO", 30.0)}]}');
            INSERT INTO exercises (name, profile_id, criteria_overrides)
              SELECT 'Drill', id, '{"channels":{"CO":{"lowAlarm":12}},"alarmLatching":true}' FROM sensor_profiles WHERE name = 'Custom';
            """.trimIndent(),
        )
        execute(v4)

        val builtin = definition("Builtin")
        assertFalse("alarmLatching" in builtin)
        val co = builtin["channels"]!!.jsonArray[0].jsonObject
        assertEquals(false, co["lowAlarmLatching"]!!.jsonPrimitive.boolean, "A1 clears itself")
        assertEquals(true, co["highAlarmLatching"]!!.jsonPrimitive.boolean, "A2 still latches")
        assertEquals(5.0, co["t90Seconds"]!!.jsonPrimitive.double, "old default response time")
        assertEquals(12.0, builtin["channels"]!!.jsonArray[1].jsonObject["t90Seconds"]!!.jsonPrimitive.double, "admin's value kept")

        val custom = definition("Custom")["channels"]!!.jsonArray[0].jsonObject
        assertEquals(false, custom["highAlarmLatching"]!!.jsonPrimitive.boolean, "latched nothing before, latches nothing now")
        assertEquals(30.0, custom["t90Seconds"]!!.jsonPrimitive.double, "custom setups keep their response time")

        val criteria = Json.parseToJsonElement(query("SELECT criteria_overrides::text FROM exercises WHERE name = 'Drill'")).jsonObject
        assertFalse("alarmLatching" in criteria)
        val override = criteria["channels"]!!.jsonObject["CO"]!!.jsonObject
        assertEquals(12.0, override["lowAlarm"]!!.jsonPrimitive.double, "existing override kept")
        assertEquals(true, override["lowAlarmLatching"]!!.jsonPrimitive.boolean, "explicit latching applies to every alarm")
        assertEquals(true, override["twaAlarmLatching"]!!.jsonPrimitive.boolean)
    }
}
