/*
 * Copyright 2021-2023 Exactpro (Exactpro Systems Limited)
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

import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.http.client.api.decorators.Th2RawHttpRequest
import com.exactpro.th2.http.client.util.CONTENT_LENGTH_HEADER
import mu.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.RequestLine
import rawhttp.core.body.BytesBody
import rawhttp.core.server.TcpRawHttpServer
import java.net.URI
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger


class ClientTest {
    companion object {
        private val LOGGER = KotlinLogging.logger { }

        private const val serverPort = 8086
        private const val body =
            """{ "id" : 901, "name" : { "first":"Tom", "middle":"and", "last":"Jerry" }, "phones" : [ {"type" : "home", "number" : "1233333" }, {"type" : "work", "number" : "264444" }], "lazy" : false, "married" : null }"""
        private var responseCount = AtomicInteger(0)

        private val server = TcpRawHttpServer(serverPort)

        private val responseData = """
          HTTP/1.1 200 OK
          Content-Type: plain/text
          Content-Length: ${body.length}
          
          $body
          """.trimIndent()

        @BeforeAll
        @JvmStatic
        fun setUp() {
            server.start {
                LOGGER.info { "Received request: ${it.eagerly().startLine}" }
                responseCount.incrementAndGet()
                Optional.of(RawHttp().parseResponse(responseData))
            }
        }

        @AfterAll
        @JvmStatic
        fun finish() {
            server.stop()
        }
    }

    @Test
    fun `Simple response test`() {
        val parentEventID = EventID.newBuilder().setId("testParentId").build()
        val metadata = mapOf("propertyOne" to "propertyOneValue", "propertyTwo" to "propertyTwoValue")
        val requestFlag = CountDownLatch(1)

        var newParentID = EventID.newBuilder().setId("testParentIdNew").build()
        var newMetadata = mapOf<String, String>()

        val prepareRequest = { request: RawHttpRequest -> request }
        val requestBody = body.toByteArray()
        val httpHeaders = RawHttpHeaders.newBuilder().apply {
            with(CONTENT_LENGTH_HEADER, requestBody.size.toString())
        }.build()

        val onRequest = { request: RawHttpRequest ->
            LOGGER.debug { "Request submitted: ${request.uri}" }
            newMetadata = (request as Th2RawHttpRequest).metadataProperties
            newParentID = request.parentEventId
        }

        val onResponse = { _: RawHttpRequest, response: RawHttpResponse<*> ->
            requestFlag.countDown()
            LOGGER.debug("Response handled: ${response.statusCode}")
        }

        val client = HttpClient(
            false,
            "localhost",
            serverPort,
            20000,
            5000,
            5,
            emptyMap(),
            prepareRequest,
            onRequest,
            onResponse
        )

        val requestLine = RequestLine("GET", URI("/test"), HttpVersion.HTTP_1_1)

        val request = Th2RawHttpRequest(
            requestLine,
            httpHeaders,
            BytesBody(requestBody).toBodyReader(),
            null,
            parentEventID,
            metadata
        )

        val response = client.send(request)

        Assertions.assertEquals(200, response.startLine.statusCode)
        Assertions.assertEquals("OK", response.startLine.reason)
        Assertions.assertEquals("plain/text", response.headers["Content-Type"][0])
        Assertions.assertEquals(body.length.toString(), response.headers["Content-Length"][0])
        Assertions.assertEquals(body, response.body.get().toString())

        Assertions.assertEquals(metadata.values.size /* method and uri */, newMetadata.values.size)
        metadata.forEach {
            Assertions.assertTrue(newMetadata.containsKey(it.key))
            Assertions.assertEquals(it.value, newMetadata[it.key])
        }
        Assertions.assertEquals(parentEventID, newParentID)
        Assertions.assertEquals(0, requestFlag.count)
    }

    @Test
    fun `multiple response test`() {
        val executor = Executors.newCachedThreadPool()

        val requestFlag = CountDownLatch(6)

        val parentEventID = EventID.newBuilder().setId("testParentId").build()
        val metadata = mapOf("propertyOne" to "propertyOneValue", "propertyTwo" to "propertyTwoValue")

        val prepareRequest = { request: RawHttpRequest -> request }
        val requestBody = body.toByteArray()
        val httpHeaders = RawHttpHeaders.newBuilder().apply {
            with(CONTENT_LENGTH_HEADER, requestBody.size.toString())
            with("Connection", "Keep-Alive")
        }.build()

        val onRequest = { request: RawHttpRequest ->
            LOGGER.info("Request submitted: ${request.startLine}")
        }

        val onResponse = { _: RawHttpRequest, response: RawHttpResponse<*> ->
            requestFlag.countDown()
            LOGGER.info("Response handled: ${response.statusCode}")
        }

        val client = HttpClient(
            false,
            "localhost",
            serverPort,
            20000,
            5000,
            2,
            emptyMap(),
            prepareRequest,
            onRequest,
            onResponse
        )
        client.start()

        val requestLine = RequestLine("GET", URI("/test"), HttpVersion.HTTP_1_1)

        repeat(requestFlag.count.toInt()) {
            executor.submit {
                client.send(
                    Th2RawHttpRequest(
                        requestLine,
                        httpHeaders,
                        BytesBody(requestBody).toBodyReader(),
                        null,
                        parentEventID,
                        metadata
                    )
                )
            }
        }

        requestFlag.await(5, TimeUnit.SECONDS)
        client.stop()

        Assertions.assertEquals(0, requestFlag.count)
    }
}