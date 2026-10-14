package com.taksolutions.simsensor.server.telemetry

import com.taksolutions.simsensor.protocol.TelemetrySample
import com.taksolutions.simsensor.server.db.Database
import com.taksolutions.simsensor.server.db.DbJson
import com.taksolutions.simsensor.server.db.JsonbParam
import com.taksolutions.simsensor.server.db.executeBatch
import com.taksolutions.simsensor.server.db.query
import com.taksolutions.simsensor.server.db.uuid
import com.taksolutions.simsensor.server.devices.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** One sample to store. */
data class TelemetryRow(val deviceId: UUID, val exerciseId: UUID?, val sample: TelemetrySample)

/** Accepts samples without blocking the device socket. */
interface TelemetrySink {
    fun enqueue(row: TelemetryRow)
}

/**
 * Buffers samples and writes them in batches about every [flushInterval]
 * (docs/DESIGN.md §3.2). The buffer is bounded; when the database falls behind, the oldest
 * samples are dropped rather than letting memory grow.
 */
class BatchingTelemetryWriter(
    private val db: Database,
    private val devices: DeviceRepository,
    private val clock: Clock,
    private val flushInterval: Duration = Duration.ofSeconds(1),
    private val maxBatch: Int = 1_000,
    capacity: Int = 100_000,
) : TelemetrySink {
    private val log = LoggerFactory.getLogger(BatchingTelemetryWriter::class.java)
    private val buffer = Channel<TelemetryRow>(capacity, BufferOverflow.DROP_OLDEST)
    private val writeLock = Mutex()

    override fun enqueue(row: TelemetryRow) {
        buffer.trySend(row)
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(flushInterval.toMillis())
                runCatching { flush() }.onFailure { log.error("Telemetry flush failed", it) }
            }
        }
    }

    /** Writes everything buffered now. Called by the timer, and by tests. */
    suspend fun flush() = writeLock.withLock {
        while (true) {
            val batch = ArrayList<TelemetryRow>(maxBatch)
            while (batch.size < maxBatch) batch += buffer.tryReceive().getOrNull() ?: break
            if (batch.isEmpty()) break
            write(batch)
        }
    }

    private suspend fun write(batch: List<TelemetryRow>) {
        val now = clock.instant()
        db.transaction {
            executeBatch(
                """
                INSERT INTO telemetry (ts, device_id, exercise_id, lat, lon, accuracy_m, battery, readings, alarms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                batch.map { row ->
                    val s = row.sample
                    arrayOf(
                        sampleTime(s.ts, now), row.deviceId, row.exerciseId, s.lat, s.lon, s.accuracyM, s.battery,
                        JsonbParam(DbJson.encodeToString(READINGS, s.readings)),
                        JsonbParam(DbJson.encodeToString(ALARMS, s.alarms)),
                    )
                },
            )
        }
        devices.touchLastSeen(batch.map { it.deviceId }.toSet(), now)
    }

    /** Device clocks can be wrong; anything far outside "recent past" is stored at server time. */
    private fun sampleTime(ts: Long, now: Instant): Instant {
        val t = Instant.ofEpochMilli(ts)
        return if (t.isBefore(now.minus(MAX_BACKLOG)) || t.isAfter(now.plus(MAX_FUTURE))) now else t
    }

    private companion object {
        val READINGS = MapSerializer(String.serializer(), Double.serializer())
        val ALARMS = ListSerializer(String.serializer())
        val MAX_BACKLOG: Duration = Duration.ofDays(7)
        val MAX_FUTURE: Duration = Duration.ofMinutes(1)
    }
}

/** Reads stored telemetry. */
class TelemetryRepository(private val db: Database) {

    /** The newest sample of each device. */
    suspend fun latest(deviceIds: Collection<UUID>): Map<UUID, TelemetrySample> {
        if (deviceIds.isEmpty()) return emptyMap()
        return db.transaction {
            query(
                """
                SELECT DISTINCT ON (device_id) * FROM telemetry
                 WHERE device_id = ANY(?) AND ts > now() - interval '1 day'
                 ORDER BY device_id, ts DESC
                """.trimIndent(),
                createArrayOf("uuid", deviceIds.toTypedArray()),
            ) { rs -> rs.uuid("device_id") to toSample(rs) }.toMap()
        }
    }

    /** Samples of one device, oldest first (tests and, later, replay). */
    suspend fun forDevice(deviceId: UUID): List<TelemetrySample> = db.transaction {
        query("SELECT * FROM telemetry WHERE device_id = ? ORDER BY ts", deviceId, map = ::toSample)
    }

    private fun toSample(rs: ResultSet) = TelemetrySample(
        ts = rs.getObject("ts", java.time.OffsetDateTime::class.java).toInstant().toEpochMilli(),
        lat = rs.getDouble("lat").takeUnless { rs.wasNull() },
        lon = rs.getDouble("lon").takeUnless { rs.wasNull() },
        accuracyM = rs.getFloat("accuracy_m").takeUnless { rs.wasNull() },
        battery = rs.getFloat("battery").takeUnless { rs.wasNull() },
        readings = DbJson.decodeFromString(MapSerializer(String.serializer(), Double.serializer()), rs.getString("readings")),
        alarms = DbJson.decodeFromString(ListSerializer(String.serializer()), rs.getString("alarms")),
    )
}
