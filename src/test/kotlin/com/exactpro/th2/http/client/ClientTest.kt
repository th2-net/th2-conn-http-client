package com.exactpro.th2.http.client

import com.exactpro.th2.http.client.api.Th2RawHttpRequest
import java.net.URI
import java.util.Optional
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
import rawhttp.core.server.TcpRawHttpServer

class ClientTest {
    companion object {
        private val LOGGER = KotlinLogging.logger { }

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
    fun `Simple response test`() {
        val parentEventID = "testParentId"
        val metadata = mapOf( "propertyOne" to "propertyOneValue", "propertyTwo" to "propertyTwoValue")

        var newParentID = ""
        var newMetadata = mapOf<String, String>()

        val prepareRequest = { request : RawHttpRequest ->  request}
        val onRequest = { request : RawHttpRequest ->
            LOGGER.debug("Request submitted: $request")
            newMetadata = (request as Th2RawHttpRequest).metadataProperties
            newParentID = request.parentEventId
        }
        val onResponse = { _ : RawHttpRequest, response : RawHttpResponse<*> ->
            LOGGER.debug("Response handled: $response")
        }

        val client = HttpClient(false, "localhost",
            serverPort, 20000, 5000, emptyMap(), prepareRequest, onRequest, onResponse)

        val requestLine =  RequestLine("GET", URI("/test"), HttpVersion.HTTP_1_1)
        val request  = Th2RawHttpRequest( requestLine, RawHttpHeaders.CONTENT_LENGTH_ZERO, null, null, parentEventID, metadata)

        val response = client.send(request)

        Assertions.assertEquals(200, response.startLine.statusCode)
        Assertions.assertEquals("OK", response.startLine.reason)
        Assertions.assertEquals("plain/text", response.headers["Content-Type"][0])
        Assertions.assertEquals(responseBody.length.toString(), response.headers["Content-Length"][0])
        Assertions.assertEquals(responseBody, response.body.get().toString())

        Assertions.assertEquals(metadata.values.size /* method and uri */, newMetadata.values.size)
        metadata.forEach {
            Assertions.assertTrue(newMetadata.containsKey(it.key))
            Assertions.assertEquals(it.value, newMetadata[it.key])
        }
        Assertions.assertEquals(parentEventID, newParentID)
    }
}