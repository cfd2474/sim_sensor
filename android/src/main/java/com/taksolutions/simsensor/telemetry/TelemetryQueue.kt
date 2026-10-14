package com.taksolutions.simsensor.telemetry

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.taksolutions.simsensor.protocol.TelemetrySample
import kotlinx.serialization.json.Json

/**
 * Samples waiting to be sent (docs/DESIGN.md §6.4). Every sample goes through the queue, so
 * nothing is lost while the network is down. Plain SQLite: no annotation processing needed.
 * Bounded: past [maxRows], the oldest samples are dropped.
 */
class TelemetryQueue(context: Context, private val maxRows: Int = 50_000) :
    SQLiteOpenHelper(context, "telemetry-queue.db", null, 1) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE queue (id INTEGER PRIMARY KEY AUTOINCREMENT, sample TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun add(sample: TelemetrySample) {
        writableDatabase.apply {
            insert("queue", null, ContentValues().apply { put("sample", json.encodeToString(TelemetrySample.serializer(), sample)) })
            execSQL("DELETE FROM queue WHERE id <= (SELECT max(id) FROM queue) - ?", arrayOf(maxRows))
        }
    }

    /** The oldest [limit] samples with their row ids. */
    @Synchronized
    fun peek(limit: Int): List<Pair<Long, TelemetrySample>> =
        readableDatabase.rawQuery("SELECT id, sample FROM queue ORDER BY id LIMIT ?", arrayOf(limit.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    runCatching { json.decodeFromString(TelemetrySample.serializer(), c.getString(1)) }
                        .onSuccess { add(c.getLong(0) to it) }
                }
            }
        }

    /** Removes everything up to and including [id]. */
    @Synchronized
    fun removeThrough(id: Long) {
        writableDatabase.execSQL("DELETE FROM queue WHERE id <= ?", arrayOf(id))
    }

    @Synchronized
    fun size(): Long = readableDatabase.rawQuery("SELECT count(*) FROM queue", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }
}
