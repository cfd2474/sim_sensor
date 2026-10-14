package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.server.support.withApp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {

    @Test
    fun healthReportsVersion() = withApp {
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"version\":\"1.2.3\""))
    }
}
