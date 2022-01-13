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

import com.exactpro.th2.http.client.api.Th2RawHttpRequest
import com.exactpro.th2.http.client.util.CONTENT_LENGTH_HEADER
import java.net.URI
import java.util.Optional
import mu.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.RequestLine
import rawhttp.core.body.BytesBody
import rawhttp.core.server.TcpRawHttpServer

class ClientTest {
    companion object {
        private val LOGGER = KotlinLogging.logger { }

        private const val serverPort = 8086
        private const val body = """{ "id" : 901, "name" : { "first":"Tom", "middle":"and", "last":"Jerry" }, "phones" : [ {"type" : "home", "number" : "1233333" }, {"type" : "work", "number" : "264444" }], "lazy" : false, "married" : null }"""

        private val server = TcpRawHttpServer(serverPort)

        private val response: RawHttpResponse<*> = RawHttp().parseResponse("""
          HTTP/1.1 200 OK
          Content-Type: plain/text
          Content-Length: ${body.length}
          
          $body
          """.trimIndent())

        @BeforeAll
        @JvmStatic
        fun setUp() {
            server.start {
                LOGGER.info { "Received request: $it" }
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
    fun `Simple response test`() {
        val parentEventID = "testParentId"
        val metadata = mapOf("propertyOne" to "propertyOneValue", "propertyTwo" to "propertyTwoValue")

        var newParentID = ""
        var newMetadata = mapOf<String, String>()

        val prepareRequest = { request: RawHttpRequest -> request }
        val requestBody = body.toByteArray()
        val httpHeaders = RawHttpHeaders.newBuilder().apply {
            with(CONTENT_LENGTH_HEADER, requestBody.size.toString())
        }.build()

        val onRequest = { request: RawHttpRequest ->
            LOGGER.debug("Request submitted: $request")
            newMetadata = (request as Th2RawHttpRequest).metadataProperties
            newParentID = request.parentEventId
        }

        val onResponse = { _: RawHttpRequest, response: RawHttpResponse<*> ->
            LOGGER.debug("Response handled: $response")
        }

        val client = HttpClient(false, "localhost", serverPort, 20000, 5000, emptyMap(), prepareRequest, onRequest, onResponse)

        val requestLine = RequestLine("GET", URI("/test"), HttpVersion.HTTP_1_1)

        val request = Th2RawHttpRequest(requestLine, httpHeaders, BytesBody(requestBody).toBodyReader(), null, parentEventID, metadata)

        val response = client.send(request)

        Assertions.assertEquals(200, response.startLine.statusCode)
        Assertions.assertEquals("OK", response.startLine.reason)
        Assertions.assertEquals("plain/text", response.headers["Content-Type"][0])
        Assertions.assertEquals(body.length.toString(), response.headers["Content-Length"][0])
        Assertions.assertEquals(body, response.body.get().toString())

//        Assertions.assertEquals(metadata.values.size /* method and uri */, newMetadata.values.size)
//        metadata.forEach {
//            Assertions.assertTrue(newMetadata.containsKey(it.key))
//            Assertions.assertEquals(it.value, newMetadata[it.key])
//        }
//        Assertions.assertEquals(parentEventID, newParentID)
    }
}