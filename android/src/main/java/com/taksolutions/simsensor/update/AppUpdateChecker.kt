package com.taksolutions.simsensor.update

import com.taksolutions.simsensor.connection.LinkStatus
import com.taksolutions.simsensor.connection.ServerUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Reads what app the server offers. */
fun interface OfferedAppSource {
    /** Null when the server has none published or can't be asked. */
    suspend fun fetch(serverUrl: String): OfferedApp?
}

/** `GET /api/app` (public): `{available, url, app: {version, versionCode, …}}`. */
class HttpOfferedAppSource(private val http: OkHttpClient) : OfferedAppSource {
    override suspend fun fetch(serverUrl: String): OfferedApp? = withContext(Dispatchers.IO) {
        val url = ServerUrls.apiUrl(serverUrl, "/api/app") ?: return@withContext null
        runCatching {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parse(response.body?.string() ?: return@use null)
            }
        }.getOrNull()
    }

    companion object {
        /** Null unless an app is published with a version and versionCode. */
        fun parse(body: String): OfferedApp? {
            val info = Json.parseToJsonElement(body).jsonObject
            if (info["available"]?.jsonPrimitive?.booleanOrNull != true) return null
            val app = info["app"] as? JsonObject ?: return null
            val version = app["version"]?.jsonPrimitive?.contentOrNull ?: return null
            val code = app["versionCode"]?.jsonPrimitive?.intOrNull ?: return null
            val download = info["url"]?.jsonPrimitive?.contentOrNull ?: return null
            return OfferedApp(version, code, download)
        }
    }
}

/**
 * Checks whether the connected server offers a newer app (operator, 2026-10-17): on every
 * connection, at most every [MIN_GAP_MS], and every [PERIOD_MS] while connected. Separate from
 * the meter: a failed check changes nothing on screen. [later] hides the prompt for that version.
 */
class AppUpdateChecker(
    private val source: OfferedAppSource,
    private val decide: (OfferedApp?) -> AppUpdate?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var found: AppUpdate? = null
    private var dismissedCode = 0
    private var lastCheck = 0L
    private var periodic: Job? = null
    private var scope: CoroutineScope? = null
    private val _update = MutableStateFlow<AppUpdate?>(null)

    /** The update to prompt for, or null. */
    val update: StateFlow<AppUpdate?> = _update

    /** [scope] must be single-threaded: the checker's state is only touched there. */
    fun start(scope: CoroutineScope, serverUrl: () -> String?, link: Flow<LinkStatus>) {
        this.scope = scope
        scope.launch {
            link.collect { status ->
                periodic?.cancel()
                if (status !is LinkStatus.Connected) return@collect
                periodic = launch {
                    if (now() - lastCheck >= MIN_GAP_MS) check(serverUrl())
                    while (isActive) {
                        delay(PERIOD_MS)
                        check(serverUrl())
                    }
                }
            }
        }
    }

    /** "Later": no prompt again until the server offers a newer version (or the app restarts). */
    fun later() {
        scope?.launch {
            found?.let { dismissedCode = it.offered.versionCode }
            publish()
        }
    }

    private suspend fun check(serverUrl: String?) {
        lastCheck = now()
        val offered = serverUrl?.let { source.fetch(it) } ?: return // keep what we had on failure
        found = decide(offered)
        publish()
    }

    private fun publish() {
        _update.value = found?.takeIf { it.offered.versionCode > dismissedCode }
    }

    companion object {
        const val MIN_GAP_MS = 10 * 60_000L
        const val PERIOD_MS = 6 * 60 * 60_000L
    }
}
