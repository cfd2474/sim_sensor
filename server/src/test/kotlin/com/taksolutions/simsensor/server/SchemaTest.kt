package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.server.support.TestDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaTest {

    @Test
    fun telemetryIsACompressedHypertable() {
        TestDatabase.dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT compression_enabled FROM timescaledb_information.hypertables WHERE hypertable_name = 'telemetry'",
                ).use { rs ->
                    assertEquals(true, rs.next(), "telemetry is not a hypertable")
                    assertEquals(true, rs.getBoolean(1), "telemetry compression is not enabled")
                }
            }
        }
    }
}
