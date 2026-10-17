package com.taksolutions.simsensor.server.system

import com.taksolutions.simsensor.profile.ValidationError
import com.taksolutions.simsensor.server.api.ConflictException
import com.taksolutions.simsensor.server.api.ValidationException
import com.taksolutions.simsensor.server.events.EventLog
import com.taksolutions.simsensor.server.events.EventTypes
import com.taksolutions.simsensor.server.instructors.InstructorPrincipal
import com.taksolutions.simsensor.server.instructors.requireAdmin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Where versions come from: the repository's tags and their release notes. */
interface ReleaseSource {
    /** Tag names, any order (non-version tags are ignored by the caller). */
    suspend fun tags(): List<String>

    /** `docs/RELEASE_NOTES.md` as of [tag], or null. */
    suspend fun releaseNotes(tag: String): String?
}

/** The public GitHub repository [repo] ("owner/name"). Unauthenticated: 60 API calls an hour, so callers cache. */
class GitHubReleaseSource(
    private val repo: String,
    private val userAgent: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : ReleaseSource {
    override suspend fun tags(): List<String> {
        val body = get("https://api.github.com/repos/$repo/tags?per_page=100") ?: return emptyList()
        return Json.parseToJsonElement(body).jsonArray.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
    }

    override suspend fun releaseNotes(tag: String): String? = get("https://raw.githubusercontent.com/$repo/$tag/docs/RELEASE_NOTES.md")

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", userAgent).timeout(Duration.ofSeconds(10)).GET().build()
        runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull()?.takeIf { it.statusCode() == 200 }?.body()
    }
}

/** Version as in `VERSION` / a `vX.Y.Z` tag. */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version) = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    override fun toString() = "$major.$minor.$patch"
    val tag get() = "v$this"

    companion object {
        private val PATTERN = Regex("""^v?(\d{1,4})\.(\d{1,4})\.(\d{1,6})$""")
        fun parse(text: String): Version? = PATTERN.matchEntire(text.trim())?.destructured?.let { (a, b, c) -> Version(a.toInt(), b.toInt(), c.toInt()) }
    }
}

@Serializable
data class UpdateStatus(
    /** idle, running, succeeded, failed, unavailable (written by the updater). */
    val state: String,
    /** update, backup or restore (v0.28.0 on; older updaters only update). */
    val action: String = UpdaterChannel.UPDATE,
    /** The backup being restored. */
    val file: String? = null,
    val step: String? = null,
    val message: String? = null,
    val tag: String? = null,
    val requestedBy: String? = null,
    val startedAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class UpdateInfo(
    val currentVersion: String,
    /** Newest version tag in the repository; null when GitHub couldn't be reached. */
    val latestVersion: String? = null,
    val updateAvailable: Boolean = false,
    /** Release notes of the versions after the current one, newest first (Markdown). */
    val releaseNotes: String? = null,
    /** The updater container is running and the install has its layout. */
    val updaterReady: Boolean = false,
    /** Why updates can't run, when they can't. */
    val updaterMessage: String? = null,
    /** Seconds since the updater last checked in (every 5 s while it runs); shows it isn't stuck. */
    val updaterSeenSecondsAgo: Long? = null,
    /** An update has been requested and not picked up yet, or is running. */
    val pending: Boolean = false,
    val status: UpdateStatus? = null,
)

/**
 * Server updates from the console (chunk 4.6, operator 2026-10-16). Shows the running and the
 * newest version, and on an admin's request writes `request.json` into [updateDir], which the
 * updater container (deploy/updater) picks up. The server itself never touches Docker.
 */
class UpdateService(
    private val currentVersion: String,
    private val updater: UpdaterChannel,
    private val releases: ReleaseSource,
    private val eventLog: EventLog,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val cacheLock = Mutex()
    private var cachedAt: Instant = Instant.EPOCH
    private var cached: Pair<Version?, String?> = null to null

    suspend fun info(actor: InstructorPrincipal): UpdateInfo {
        requireAdmin(actor)
        val current = Version.parse(currentVersion)
        val (latest, notes) = latest()
        val status = updater.status()
        val (ready, why) = updater.readiness(status)
        return UpdateInfo(
            currentVersion = currentVersion,
            latestVersion = latest?.toString(),
            updateAvailable = latest != null && current != null && latest > current,
            releaseNotes = notes?.let { sectionsAfter(it, current) },
            updaterReady = ready,
            updaterMessage = why,
            updaterSeenSecondsAgo = updater.secondsSinceHeartbeat(),
            pending = updater.busy(status),
            status = status,
        )
    }

    suspend fun request(actor: InstructorPrincipal, tag: String) {
        requireAdmin(actor)
        val wanted = Version.parse(tag) ?: invalid("That is not a version (e.g. v1.2.3)")
        val current = Version.parse(currentVersion)
        val (ready, why) = updater.readiness()
        if (!ready) throw ConflictException(why ?: "Server updates are not available")
        if (updater.busy()) throw ConflictException("An update, backup or restore is already under way")
        if (current != null && wanted <= current) invalid("$wanted is not newer than the running $current")
        val tags = releases.tags().mapNotNull(Version::parse)
        if (wanted !in tags) invalid("${wanted.tag} was not found on GitHub")
        updater.submit(UpdaterChannel.UPDATE, mapOf("tag" to wanted.tag), actor.email)
        eventLog.record(EventTypes.SERVER_UPDATE_REQUESTED, actorId = actor.id, data = mapOf("from" to currentVersion, "to" to wanted.toString()))
    }

    private suspend fun latest(): Pair<Version?, String?> = cacheLock.withLock {
        if (Duration.between(cachedAt, clock.instant()) < CACHE) return@withLock cached
        val newest = runCatching { releases.tags() }.getOrDefault(emptyList()).mapNotNull(Version::parse).maxOrNull()
        val notes = newest?.let { runCatching { releases.releaseNotes(it.tag) }.getOrNull() }
        cached = newest to notes
        // A failed lookup (no network, rate limit) is retried after a minute, not an hour.
        cachedAt = if (newest == null) clock.instant().minus(CACHE).plus(RETRY) else clock.instant()
        cached
    }

    private fun invalid(message: String): Nothing = throw ValidationException(listOf(ValidationError(null, "tag", message)))

    companion object {
        private val CACHE: Duration = Duration.ofMinutes(10)
        private val RETRY: Duration = Duration.ofMinutes(1)

        /** The `## vX.Y.Z — date` sections newer than [current], as written in RELEASE_NOTES.md. */
        fun sectionsAfter(notes: String, current: Version?): String? {
            val sections = Regex("""(?m)^## (v\d+\.\d+\.\d+)[^\n]*\n""").findAll(notes).toList()
            val parts = sections.mapIndexedNotNull { i, m ->
                val v = Version.parse(m.groupValues[1]) ?: return@mapIndexedNotNull null
                if (current != null && v <= current) return@mapIndexedNotNull null
                val end = sections.getOrNull(i + 1)?.range?.first ?: notes.length
                notes.substring(m.range.first, end).trim()
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
        }
    }
}
