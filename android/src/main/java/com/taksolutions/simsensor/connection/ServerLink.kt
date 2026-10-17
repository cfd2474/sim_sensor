package com.taksolutions.simsensor.connection

import com.taksolutions.simsensor.protocol.Hello
import com.taksolutions.simsensor.protocol.ProtocolCodec
import com.taksolutions.simsensor.protocol.ProtocolMessage
import com.taksolutions.simsensor.protocol.Welcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

/** State of the link to the server. */
sealed interface LinkStatus {
    data object Disconnected : LinkStatus
    data object Connecting : LinkStatus
    data class Connected(val serverVersion: String) : LinkStatus

    /** Waiting before the next attempt. */
    data class Retrying(val reason: String, val inMillis: Long) : LinkStatus

    /** The server refused the device token: the device was revoked. Not retried. */
    data object Rejected : LinkStatus
}

/** Reconnect delays: exponential with full jitter, so a fleet doesn't reconnect in lockstep. */
class Backoff(
    private val baseMillis: Long = 1_000,
    private val maxMillis: Long = 30_000,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    fun next(): Long {
        val cap = minOf(maxMillis, baseMillis shl minOf(attempt, 15))
        attempt++
        return baseMillis / 2 + random.nextLong(cap - baseMillis / 2 + 1)
    }

    fun reset() {
        attempt = 0
    }
}

/**
 * The device WebSocket (docs/PROTOCOL.md §2): authenticates with the device token, says hello,
 * reconnects with backoff until stopped. Incoming messages (including `welcome`) are published
 * on [incoming].
 */
class ServerLink(
    private val http: OkHttpClient,
    private val appVersion: String,
    private val model: String,
    private val hardwareId: String? = null,
) {
    private val _status = MutableStateFlow<LinkStatus>(LinkStatus.Disconnected)
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _incoming = MutableSharedFlow<ProtocolMessage>(extraBufferCapacity = 64)
    val incoming: SharedFlow<ProtocolMessage> = _incoming.asSharedFlow()

    @Volatile private var socket: WebSocket? = null
    private var loop: Job? = null

    /** Connects and keeps the connection up until [stop]. */
    fun start(scope: CoroutineScope, serverUrl: String, deviceToken: String) {
        stop()
        val url = ServerUrls.deviceSocketUrl(serverUrl) ?: run {
            _status.value = LinkStatus.Retrying("Invalid server URL", Long.MAX_VALUE)
            return
        }
        loop = scope.launch {
            val backoff = Backoff()
            while (true) {
                _status.value = LinkStatus.Connecting
                val outcome = connectOnce(url, deviceToken) { backoff.reset() }
                if (outcome == Outcome.REJECTED) {
                    _status.value = LinkStatus.Rejected
                    return@launch
                }
                val wait = backoff.next()
                _status.value = LinkStatus.Retrying(outcome.reason ?: "Connection lost", wait)
                delay(wait)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        socket?.close(1000, null)
        socket = null
        _status.value = LinkStatus.Disconnected
    }

    /** Queues [message] for sending. False when not connected. */
    fun send(message: ProtocolMessage): Boolean {
        if (_status.value !is LinkStatus.Connected) return false
        return socket?.send(ProtocolCodec.encode(message)) ?: false
    }

    private class Outcome(val reason: String?) {
        companion object {
            val REJECTED = Outcome("rejected")
        }
    }

    /** One connection attempt; returns when the socket ends. */
    private suspend fun connectOnce(url: String, token: String, onWelcome: () -> Unit): Outcome {
        val ended = Channel<Outcome>(1)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(ProtocolCodec.encode(Hello(appVersion = appVersion, model = model, hardwareId = hardwareId)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = ProtocolCodec.decodeOrNull(text) ?: return
                if (message is Welcome) {
                    _status.value = LinkStatus.Connected(message.serverVersion)
                    onWelcome()
                }
                _incoming.tryEmit(message)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ended.trySend(Outcome(reason.ifBlank { "Closed by server" }))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ended.trySend(if (response?.code == 401) Outcome.REJECTED else Outcome(t.message ?: t.javaClass.simpleName))
            }
        })
        return ended.receive()
    }
}
