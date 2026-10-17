package com.taksolutions.simsensor.server.system

import com.taksolutions.simsensor.server.api.ConflictException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock

/**
 * The shared folder between the server and the updater container (deploy/updater): requests go in
 * as `request.json` ({"action": update | backup | restore | inventory, ...}), the updater reports in
 * `status.json`, `backups.json` and `heartbeat`. Used by [UpdateService] and [BackupService]; the
 * server itself never touches Docker.
 */
class UpdaterChannel(val dir: File?, private val clock: Clock = Clock.systemUTC()) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun status(): UpdateStatus? = read("status.json")?.let { runCatching { json.decodeFromString(UpdateStatus.serializer(), it) }.getOrNull() }

    fun heartbeat(): Long? = read("heartbeat")?.trim()?.toLongOrNull()

    fun secondsSinceHeartbeat(): Long? = heartbeat()?.let { (clock.instant().epochSecond - it).coerceAtLeast(0) }

    /** An action (not a quick inventory) is waiting for the updater, being worked on, or running. */
    fun busy(status: UpdateStatus? = status()): Boolean =
        status?.state == "running" || listOf("request.json", "processing.json").any { name -> actionOf(name)?.let { it != INVENTORY } == true }

    /** Updates and backups can be handed over; the reason when they can't. */
    fun readiness(status: UpdateStatus? = status()): Pair<Boolean, String?> {
        if (dir == null) return false to "Server updates aren't set up on this install"
        if (status?.state == "unavailable") return false to (status.message ?: "This install doesn't use the updater layout")
        val beat = heartbeat() ?: return false to "The updater isn't running (see the README: Updating the server)"
        // Updaters before v0.27.0 don't beat during an update: busy, not gone.
        val working = status?.state == "running" || File(dir, "processing.json").exists()
        if (!working && clock.instant().epochSecond - beat > HEARTBEAT_SECONDS) return false to "The updater isn't responding"
        return true to null
    }

    /** Hands [action] to the updater. Fails when it can't take one or another is under way. */
    suspend fun submit(action: String, fields: Map<String, String>, requestedBy: String) {
        val status = status()
        val (ready, why) = readiness(status)
        if (!ready) throw ConflictException(why ?: "The updater is not available")
        if (busy(status)) throw ConflictException("An update, backup or restore is already under way")
        write(action, fields, requestedBy) // replaces a waiting inventory request
    }

    /** Asks for a fresh backup list unless something is already waiting. Never fails. */
    suspend fun requestInventory() {
        if (dir == null || File(dir, "request.json").exists()) return
        runCatching { write(INVENTORY, emptyMap(), "server") }
    }

    /** Writes [name] in the folder atomically (the updater may read it at any moment). */
    suspend fun writeFile(name: String, text: String) {
        val d = dir ?: throw ConflictException("Server updates aren't set up on this install")
        withContext(Dispatchers.IO) {
            val tmp = File(d, "$name.tmp")
            tmp.writeText(text)
            runCatching { Files.move(tmp.toPath(), File(d, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                .onFailure { throw ConflictException("Couldn't hand the file to the updater") }
        }
    }

    fun read(name: String): String? = dir?.let { File(it, name) }?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    fun modifiedSecondsAgo(name: String): Long? =
        dir?.let { File(it, name) }?.takeIf { it.isFile }?.let { (clock.millis() - it.lastModified()).coerceAtLeast(0) / 1000 }

    private suspend fun write(action: String, fields: Map<String, String>, requestedBy: String) {
        val body = JsonObject(
            mapOf("action" to JsonPrimitive(action)) + fields.mapValues { JsonPrimitive(it.value) } +
                mapOf("requestedBy" to JsonPrimitive(requestedBy), "requestedAt" to JsonPrimitive(clock.instant().toString())),
        )
        writeFile("request.json", json.encodeToString(JsonObject.serializer(), body))
    }

    /** The action in [name]; requests without one (before v0.28.0) are updates. */
    private fun actionOf(name: String): String? {
        if (dir == null || !File(dir, name).exists()) return null
        val text = read(name) ?: return UPDATE
        return runCatching { json.parseToJsonElement(text).jsonObject["action"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: UPDATE
    }

    companion object {
        const val UPDATE = "update"
        const val INVENTORY = "inventory"
        private const val HEARTBEAT_SECONDS = 30
    }
}
