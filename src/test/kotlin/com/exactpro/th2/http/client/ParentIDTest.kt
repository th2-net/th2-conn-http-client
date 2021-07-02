package com.exactpro.th2.http.client

import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.toTimestamp
import com.exactpro.th2.http.client.api.Th2RawHttpRequest
import com.exactpro.th2.http.client.util.toBatch
import com.exactpro.th2.http.client.util.toRequest
import java.net.URI
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RequestLine

class ParentIDTest {
    companion object {
        private const val serverPort = 8086
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

        val requestLine = RequestLine("GET", URI("/test"), HttpVersion.HTTP_1_1).withHost("localhost:$serverPort")
        val request = MessageGroup.newBuilder().addMessages(AnyMessage.newBuilder().setRawMessage(message).build()).build()
                .toRequest()
                .withRequestLine(requestLine)
                .withBody(null)
                .withHeaders(RawHttpHeaders.CONTENT_LENGTH_ZERO)

        if (request is Th2RawHttpRequest) {
            assertEquals(request.parentEventId, "123")
        } else {
            fail("Request type isn't Th2RawHttpRequest")
        }
    }

    @Test
    fun `Request parent id to Response parent id to Request parent id, check data loss`() {
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

        val requestLine = RequestLine("GET", URI("/test"), HttpVersion.HTTP_1_1).withHost("localhost:$serverPort")
        var request = MessageGroup.newBuilder().addMessages(AnyMessage.newBuilder().setRawMessage(message).build()).build()
                .toRequest()
                .withRequestLine(requestLine)
                .withBody(null)
                .withHeaders(RawHttpHeaders.CONTENT_LENGTH_ZERO)

        val response = RawHttp().parseResponse(
            "HTTP/1.1 200 OK\n" +
                    "Content-Type: text/plain\n" +
                    "Content-Length: 9\n" +
                    "\n" +
                    "something"
        )
        val connectionId = ConnectionID.newBuilder().setSessionAlias("testAlias").build()

        val messageGroup = response.toBatch(connectionId, 0L, request)

        request = messageGroup.getGroups(0).toRequest()

        if (request is Th2RawHttpRequest) {
            assertEquals(request.parentEventId, "123")
        } else {
            fail("Request type isn't Th2RawHttpRequest")
        }
    }
}