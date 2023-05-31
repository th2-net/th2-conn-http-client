/*
 * Copyright 2021-2022 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exactpro.th2.http.client

import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.toTimestamp
import com.exactpro.th2.http.client.api.decorators.Th2RawHttpRequest
import com.exactpro.th2.http.client.util.toBatch
import com.exactpro.th2.http.client.util.toRawMessage
import com.exactpro.th2.http.client.util.toRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RequestLine
import java.net.URI
import java.time.Instant

class RequestPropertiesPassthroughTest {
    companion object {
        private const val serverPort = 8086
        private const val BOOK_NAME = "bookName"
    }

    @Test
    fun `Request id and properties test`() {
        val parentEventId = "123"
        val metadataProperties = mapOf("abc" to "cde")

        val message = RawMessage.newBuilder().apply {
            this.parentEventIdBuilder.id = parentEventId
            this.metadataBuilder.apply {
                putAllProperties(metadataProperties)
                this.idBuilder.apply {
                    this.connectionId = ConnectionID.getDefaultInstance()
                    this.direction = Direction.FIRST
                    this.sequence = 123
                    this.timestamp = Instant.now().toTimestamp()
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
            assertEquals(parentEventId, request.parentEventId)
            assertEquals(metadataProperties, request.metadataProperties)
        } else {
            fail("Request type isn't Th2RawHttpRequest")
        }
    }

    @Test
    fun `RawMessage to Request, to Response data, check id or properties loss`() {
        val parentEventId = "123"
        val metadataProperties = mapOf("abc" to "cde")

        val message = RawMessage.newBuilder().apply {
            this.parentEventIdBuilder.id = parentEventId
            this.metadataBuilder.apply {
                putAllProperties(metadataProperties)
                this.idBuilder.apply {
                    this.connectionId = ConnectionID.getDefaultInstance()
                    this.direction = Direction.FIRST
                    this.sequence = 123
                    this.timestamp = Instant.now().toTimestamp()
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

        val messageGroup = response.toRawMessage(connectionId, 0L, BOOK_NAME, request).toBatch()

        request = messageGroup.getGroups(0).toRequest()

        if (request is Th2RawHttpRequest) {
            assertEquals(parentEventId, request.parentEventId)
            assertTrue(request.metadataProperties.entries.containsAll(metadataProperties.entries))
        } else {
            fail("Request type isn't Th2RawHttpRequest")
        }
    }

    @Test
    fun `Checking the correct filtering for headers prefixed with header-`() {
        val parentEventId = "123"
        val metadataProperties = mapOf("HEADER-first" to "10", "Header-second" to "3", "header-third" to "7")

        val message = RawMessage.newBuilder().apply {
            this.parentEventIdBuilder.id = parentEventId
            this.metadataBuilder.apply {
                putAllProperties(metadataProperties)
                this.idBuilder.apply {
                    this.connectionId = ConnectionID.getDefaultInstance()
                    this.direction = Direction.FIRST
                    this.sequence = 123
                    this.timestamp = Instant.now().toTimestamp()
                }
            }
        }.build()

        val requestLine = RequestLine("GET", URI("/test"), HttpVersion.HTTP_1_1).withHost("localhost:$serverPort")
        val request = MessageGroup.newBuilder().addMessages(AnyMessage.newBuilder().setRawMessage(message).build()).build()
            .toRequest()
            .withRequestLine(requestLine)
            .withBody(null)
            .withHeaders(RawHttpHeaders.CONTENT_LENGTH_ZERO)

        val header = RawHttpHeaders.newBuilder()
            .with("first", "10")
            .with("second", "3")
            .with("third", "7")
            .build()

        val example = RawHttpRequest(null, header, null, null)
            .withHeaders(RawHttpHeaders.CONTENT_LENGTH_ZERO).withRequestLine(requestLine)

        if (example is RawHttpRequest) {
            println(example.headers)
            println(request.headers)
            assertTrue(example.headers.headerNames.containsAll(request.headers.headerNames))
        } else {
            fail("Request type isn't Th2RawHttpRequest")
        }
    }
}