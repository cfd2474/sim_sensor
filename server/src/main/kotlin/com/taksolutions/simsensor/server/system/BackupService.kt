package com.taksolutions.simsensor.server.system

import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.events.EventLog
import com.taksolutions.simsensor.server.events.EventTypes
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.instructors.requireAdmin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupFile(
    /** File name in the install's backups folder, e.g. manual-20261017T052211Z.dump. */
    val name: String,
    val sizeBytes: Long,
    val createdAt: String,
) {
    /** before-update, manual or before-restore, from the name the updater gives it. */
    val kind: String = when {
        name.startsWith("pre-restore-") -> "before-restore"
        name.startsWith("manual-") -> "manual"
        name.startsWith("pre-v") -> "before-update"
        else -> "other"
    }
}

/** `update/backups.json`, written by the updater after every action. */
@Serializable
data class BackupInventory(val backups: List<BackupFile> = emptyList(), val retentionDays: Int? = null, val inventoryAt: String? = null)

@Serializable
data class BackupsInfo(
    /** Newest first. Empty until the updater has listed them. */
    val backups: List<BackupFile>,
    /** Backups older than this are deleted (the newest one is always kept). */
    val retentionDays: Int,
    val updaterReady: Boolean,
    val updaterMessage: String? = null,
    /** An update, backup or restore is waiting or running. */
    val busy: Boolean,
    /** When the updater last listed the backups; null before the first list. */
    val inventoryAt: String? = null,
)

@Serializable
private data class Retention(val days: Int)

/**
 * Database backups from the console (chunk 4.9, operator 2026-10-17): the list, a backup now, a
 * restore, and how long backups are kept. The work is done by the updater; this hands it requests
 * through [updater] and reads what it reports.
 */
class BackupService(private val updater: UpdaterChannel, private val eventLog: EventLog) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun info(actor: InstructorPrincipal): BackupsInfo {
        requireAdmin(actor)
        val inventory = inventory()
        // A missing or stale list (backups age out by time): ask the updater for a new one.
        if ((updater.modifiedSecondsAgo(INVENTORY_FILE) ?: Long.MAX_VALUE) > INVENTORY_MAX_AGE_SECONDS) updater.requestInventory()
        val status = updater.status()
        val (ready, why) = updater.readiness(status)
        return BackupsInfo(
            backups = inventory?.backups.orEmpty(),
            retentionDays = retentionDays(),
            updaterReady = ready,
            updaterMessage = why,
            busy = updater.busy(status),
            inventoryAt = inventory?.inventoryAt,
        )
    }

    suspend fun backup(actor: InstructorPrincipal) {
        requireAdmin(actor)
        updater.submit("backup", emptyMap(), actor.email)
        eventLog.record(EventTypes.SERVER_BACKUP_REQUESTED, actorId = actor.id)
    }

    suspend fun restore(actor: InstructorPrincipal, name: String) {
        requireAdmin(actor)
        if (!NAME.matches(name)) invalid("name", "Not a backup file name")
        if (inventory()?.backups?.none { it.name == name } != false) invalid("name", "$name is not in the backup list")
        updater.submit("restore", mapOf("file" to name), actor.email)
        eventLog.record(EventTypes.SERVER_RESTORE_REQUESTED, actorId = actor.id, data = mapOf("file" to name))
    }

    suspend fun setRetention(actor: InstructorPrincipal, days: Int) {
        requireAdmin(actor)
        if (days !in MIN_DAYS..MAX_DAYS) invalid("days", "Keep backups for $MIN_DAYS to $MAX_DAYS days")
        updater.writeFile(RETENTION_FILE, json.encodeToString(Retention.serializer(), Retention(days)))
        eventLog.record(EventTypes.SERVER_BACKUP_RETENTION_CHANGED, actorId = actor.id, data = mapOf("days" to days.toString()))
        updater.requestInventory() // apply it now, not at the next action
    }

    private fun retentionDays(): Int =
        updater.read(RETENTION_FILE)?.let { runCatching { json.decodeFromString(Retention.serializer(), it).days }.getOrNull() }
            ?.takeIf { it in MIN_DAYS..MAX_DAYS } ?: DEFAULT_DAYS

    private fun inventory(): BackupInventory? =
        updater.read(INVENTORY_FILE)?.let { runCatching { json.decodeFromString(BackupInventory.serializer(), it) }.getOrNull() }

    private fun invalid(field: String, message: String): Nothing = throw ValidationException(listOf(ValidationError(null, field, message)))

    companion object {
        const val DEFAULT_DAYS = 7
        const val MIN_DAYS = 1
        const val MAX_DAYS = 365
        private const val INVENTORY_FILE = "backups.json"
        private const val RETENTION_FILE = "retention.json"
        private const val INVENTORY_MAX_AGE_SECONDS = 600L
        /** As update.sh checks it: a plain file name, never a path. */
        private val NAME = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,120}\.dump$""")
    }
}
