package com.taksolutions.simsensor.server.support

import com.taksolutions.simsensor.server.DatabaseConfig
import com.taksolutions.simsensor.server.db.Database
import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

private class TimescaleContainer(image: DockerImageName) : GenericContainer<TimescaleContainer>(image)

/**
 * One TimescaleDB container for the whole test run (same image as production), migrated once.
 * Tests call [reset] to start from empty tables.
 */
object TestDatabase {
    private const val USER = "simsensor"
    private const val PASSWORD = "test-password"
    private const val DB = "simsensor"

    private val container: TimescaleContainer by lazy {
        val image = System.getProperty("simsensor.test.dbImage") ?: "timescale/timescaledb:2.22.1-pg16"
        TimescaleContainer(DockerImageName.parse(image))
            .withEnv("POSTGRES_USER", USER)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .withEnv("POSTGRES_DB", DB)
            .withExposedPorts(5432)
            // The image restarts Postgres once after its init scripts, so the message appears twice.
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
            .withStartupTimeout(Duration.ofMinutes(3))
            .also { it.start() }
    }

    val dataSource: HikariDataSource by lazy {
        val url = "jdbc:postgresql://${container.host}:${container.getMappedPort(5432)}/$DB"
        Database.createDataSource(DatabaseConfig(url, USER, PASSWORD, maxPoolSize = 5)).also(Database::migrate)
    }

    fun reset() {
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute(
                    """
                    TRUNCATE instructors, instructor_sessions, sensor_profiles, exercises, exercise_instructors,
                             exercise_sessions, device_groups, devices, enrollment_tokens, event_log, telemetry
                    RESTART IDENTITY CASCADE
                    """.trimIndent(),
                )
            }
        }
    }
}
