package com.exactpro.th2.http.client

import com.exactpro.th2.http.client.util.ServerIncluded
import com.exactpro.th2.http.client.util.simpleTestSingle
import org.junit.jupiter.api.Test
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RequestLine
import rawhttp.core.body.EagerBodyReader
import java.net.URI

class RequestMethodsTests: ServerIncluded() {

    @Test
    fun `GET response test`() = simpleTestSingle { port ->
        RawHttpRequest(RequestLine("GET", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder().apply {
            with("Host", "localhost:$port")
            with("Connection", "close")
        }.build(), EagerBodyReader("""{ "key":"value" }""".toByteArray()), null)
    }

    @Test
    fun `POST response test`() = simpleTestSingle { port ->
        RawHttpRequest(RequestLine("POST", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder().apply {
            with("Host", "localhost:$port")
            with("Connection", "close")
        }.build(), EagerBodyReader("""{ "key":"value" }""".toByteArray()), null)
    }

    @Test
    fun `PUT response test`() = simpleTestSingle { port ->
        RawHttpRequest(RequestLine("PUT", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder().apply {
            with("Host", "localhost:$port")
            with("Connection", "close")
        }.build(), EagerBodyReader("""{ "key":"value" }""".toByteArray()), null)
    }

    @Test
    fun `DELETE response test`() = simpleTestSingle { port ->
        RawHttpRequest(RequestLine("DELETE", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder().apply {
            with("Host", "localhost:$port")
            with("Connection", "close")
        }.build(), EagerBodyReader("""{ "key":"value" }""".toByteArray()), null)
    }

    @Test
    fun `TRACE response test`() = simpleTestSingle { port ->
        RawHttpRequest(RequestLine("TRACE", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder().apply {
            with("Host", "localhost:$port")
            with("Connection", "close")
        }.build(), EagerBodyReader("""{ "key":"value" }""".toByteArray()), null)
    }

    @Test
    fun `PATCH response test`() = simpleTestSingle { port ->
        RawHttpRequest(RequestLine("PATCH", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder().apply {
            with("Host", "localhost:$port")
            with("Connection", "close")
        }.build(), EagerBodyReader("""{ "key":"value" }""".toByteArray()), null)
    }

    @Test
    fun `OPTIONS response test`() = simpleTestSingle { port ->
        RawHttpRequest(RequestLine("OPTIONS", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder().apply {
            with("Host", "localhost:$port")
            with("Connection", "close")
        }.build(), EagerBodyReader("""{ "key":"value" }""".toByteArray()), null)
    }

    @Test
    fun `CONNECT response test`() = simpleTestSingle(false, false) { port ->
        RawHttpRequest(RequestLine("CONNECT", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder()
            .apply {
                with("Host", "localhost:$port")
                with("Connection", "close")
            }
            .build(), EagerBodyReader("""{ "key":"value" }""".toByteArray()), null)
    }

    @Test
    fun `HEAD response test`() = simpleTestSingle(false, true) { port ->
        RawHttpRequest(RequestLine("HEAD", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder()
            .apply {
                with("Host", "localhost:$port")
                with("Connection", "close")
            }
            .build(), EagerBodyReader("""{ "key":"value" }""".toByteArray()), null)
    }
}