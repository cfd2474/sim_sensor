package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.protocol.ErrorMessage
import com.taksolutions.simsensor.protocol.Hello
import com.taksolutions.simsensor.protocol.ProtocolCodec
import com.taksolutions.simsensor.protocol.Welcome
import com.taksolutions.simsensor.server.device.DeviceMessageHandler
import com.taksolutions.simsensor.server.routes.DEVICE_SOCKET_PATH
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerTest {
    private val config = ServerConfig(version = "1.2.3")

    @Test
    fun healthReportsVersion() = testApplication {
        application { module(config) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"version\":\"1.2.3\""))
    }

    @Test
    fun helloGetsWelcome() = testApplication {
        application { module(config, DeviceMessageHandler(serverVersion = "1.2.3", clock = { 42L })) }
        val ws = createClient { install(WebSockets) }
        ws.webSocket(DEVICE_SOCKET_PATH) {
            send(Frame.Text(ProtocolCodec.encode(Hello(appVersion = "0.1.0"))))
            val reply = ProtocolCodec.decodeOrNull((incoming.receive() as Frame.Text).readText())
            assertEquals(Welcome(serverTimeMillis = 42L, serverVersion = "1.2.3"), reply)
        }
    }

    @Test
    fun garbageGetsError() {
        assertIs<ErrorMessage>(DeviceMessageHandler(serverVersion = "x").handle("nonsense"))
    }
}
