package com.exactpro.th2.http.client

import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.toTimestamp
import com.exactpro.th2.http.client.api.Th2RawHttpRequest
import com.exactpro.th2.http.client.util.toRequest
import mu.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import rawhttp.core.server.TcpRawHttpServer
import java.time.Instant
import java.util.Optional
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpRequest

import rawhttp.core.RawHttpResponse

private val LOGGER = KotlinLogging.logger { }

class ResponseTest {
    companion object {
        private const val serverPort = 8086
        private const val responseBody = "{\"Value\": \"Test\"}"

        private val server = TcpRawHttpServer(serverPort)

        private val response: RawHttpResponse<*> = RawHttp().parseResponse(
            """
          HTTP/1.1 200 OK
          Content-Type: plain/text
          Content-Length: ${responseBody.length}
          
          $responseBody
          """.trimIndent()
        )

        @BeforeAll
        @JvmStatic
        fun setUp() {
            server.start {
                Optional.of(response)
            }
        }

        @AfterAll
        @JvmStatic
        fun finish() {
            server.stop()
        }
    }

    @Test
    fun `Test to for client behavior`() {
        val testParentEventId = "123"

        var callbackRequest : RawHttpRequest? = null
        var callbackResponse : RawHttpResponse<*>? = null

        val prepareRequest = { request : RawHttpRequest ->  request}
        val onRequest = { request : RawHttpRequest ->  LOGGER.debug("Request submitted: $request") }
        val onResponse = { request : RawHttpRequest, response : RawHttpResponse<*> ->
            callbackRequest = request
            callbackResponse = response
            LOGGER.debug("Response handled: $response")
        }


        val client = HttpClient(false, "localhost", serverPort, 20000, 5000, emptyMap(), prepareRequest, onRequest, onResponse)
        val message = RawMessage.newBuilder().apply {
            this.parentEventIdBuilder.id = testParentEventId
            this.metadataBuilder.apply {
                this.timestamp = Instant.now().toTimestamp()
                this.idBuilder.apply {
                    this.connectionId = ConnectionID.getDefaultInstance()
                    this.direction = Direction.FIRST
                    this.sequence = 123
                }
            }
        }.build()

        val messageGroup = MessageGroup.newBuilder()
        messageGroup.addMessages(AnyMessage.newBuilder().setRawMessage(message).build())

        client.send(messageGroup.build().toRequest())

        assertNotNull(callbackRequest, "Request wasn't handled by onResponse callback")
        assertNotNull(callbackResponse, "Response wasn't handled by onResponse callback")

        if (callbackRequest is Th2RawHttpRequest) {
            assertEquals((callbackRequest as Th2RawHttpRequest).parentEventId,"123")
        } else {
            fail("Request type isn't Th2RawHttpRequest")
        }

        assertEquals(responseBody, callbackResponse!!.body.get().toString())


    }
}