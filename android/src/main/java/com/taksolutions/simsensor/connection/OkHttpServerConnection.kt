package com.taksolutions.simsensor.connection

import com.taksolutions.simsensor.protocol.ErrorMessage
import com.taksolutions.simsensor.protocol.Hello
import com.taksolutions.simsensor.protocol.ProtocolCodec
import com.taksolutions.simsensor.protocol.Welcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * [ServerConnection] over an OkHttp WebSocket. Authenticates with the device token, sends
 * [Hello] on open and waits for [Welcome]. Without a token (not enrolled yet) the server
 * refuses the socket; enrollment arrives in chunk 1.3.
 */
class OkHttpServerConnection(
    private val appVersion: String,
    private val deviceToken: () -> String?,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ServerConnection {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var socket: WebSocket? = null

    override fun connect(serverUrl: String) {
        disconnect()
        val url = ServerUrls.deviceSocketUrl(serverUrl)
        if (url == null) {
            _state.value = ConnectionState.Failed("Invalid server URL")
            return
        }
        _state.value = ConnectionState.Connecting
        val request = Request.Builder().url(url).apply {
            deviceToken()?.let { header("Authorization", "Bearer $it") }
        }.build()
        socket = client.newWebSocket(request, Listener())
    }

    override fun disconnect() {
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        _state.value = ConnectionState.Idle
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(ProtocolCodec.encode(Hello(appVersion = appVersion, model = android.os.Build.MODEL)))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val message = ProtocolCodec.decodeOrNull(text)) {
                is Welcome -> _state.value = ConnectionState.Connected(
                    serverVersion = message.serverVersion,
                    clockOffsetMillis = message.serverTimeMillis - clock(),
                )
                is ErrorMessage -> _state.value = ConnectionState.Failed(message.message)
                else -> Unit // later chunks handle profile/exercise/override messages
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket === socket) _state.value = ConnectionState.Failed(t.message ?: t.javaClass.simpleName)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket === socket) _state.value = ConnectionState.Idle
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
