package com.exactpro.th2.http.client

import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.toTimestamp
import com.exactpro.th2.http.client.api.Th2RawHttpRequest
import com.exactpro.th2.http.client.util.toRequest
import java.net.URI
import mu.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import rawhttp.core.server.TcpRawHttpServer
import java.time.Instant
import java.util.Optional
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpResponse
import rawhttp.core.RequestLine

class RequestTest {
    companion object {
        private val LOGGER = KotlinLogging.logger { }

        private const val serverPort = 8086

        private val server = TcpRawHttpServer(serverPort)

        private val response: RawHttpResponse<*> = RawHttp().parseResponse(
            """
            HTTP/1.1 200 OK
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
    fun `Request parent id test`() {
        val testParentEventId = "123"

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

        val requestLine =  RequestLine("GET", URI("/test"), HttpVersion.HTTP_1_1).withHost("localhost:$serverPort")
        val request = MessageGroup.newBuilder().addMessages(AnyMessage.newBuilder().setRawMessage(message).build()).build().toRequest()
            .withRequestLine(requestLine)
            .withBody(null)
            .withHeaders(RawHttpHeaders.CONTENT_LENGTH_ZERO)

        if (request is Th2RawHttpRequest) {
            assertEquals(request.parentEventId,"123")
        } else {
            fail("Request type isn't Th2RawHttpRequest")
        }
    }
}