package com.exactpro.th2.http.client

import com.exactpro.th2.http.client.util.ServerIncluded
import com.exactpro.th2.http.client.util.simpleTest
import org.junit.jupiter.api.Test
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RequestLine
import rawhttp.core.body.EagerBodyReader
import java.net.URI

class RequestMethodsTests: ServerIncluded() {

    companion object {
        const val `test request count`: Int = 3
    }

    @Test
    fun `GET response test`() = simpleTest { port ->
        mutableListOf<RawHttpRequest>().apply {
            repeat(`test request count`) {
                val body = """{ "key":"$it" }"""
                this += RawHttpRequest(RequestLine("GET", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder()
                    .apply {
                        with("Host", "localhost:$port")
                        with("Connection", "close")
                        with("Content-Length", body.length.toString())
                    }
                    .build(), EagerBodyReader(body.toByteArray()), null)
            }
        }
    }

    @Test
    fun `POST response test`() = simpleTest { port ->
        mutableListOf<RawHttpRequest>().apply {
            repeat(`test request count`) {
                val body = """{ "key":"$it" }"""
                this += RawHttpRequest(RequestLine("POST", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder()
                    .apply {
                        with("Host", "localhost:$port")
                        with("Connection", "close")
                        with("Content-Length", body.length.toString())

                    }
                    .build(), EagerBodyReader(body.toByteArray()), null)
            }
        }
    }

    @Test
    fun `PUT response test`() = simpleTest { port ->
        mutableListOf<RawHttpRequest>().apply {
            repeat(`test request count`) {
                val body = """{ "key":"$it" }"""
                this += RawHttpRequest(RequestLine("PUT", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder()
                    .apply {
                        with("Host", "localhost:$port")
                        with("Connection", "close")
                        with("Content-Length", body.length.toString())
                    }
                    .build(), EagerBodyReader(body.toByteArray()), null)
            }
        }
    }

    @Test
    fun `DELETE response test`() = simpleTest { port ->
        mutableListOf<RawHttpRequest>().apply {
            repeat(`test request count`) {
                val body = """{ "key":"$it" }"""
                this += RawHttpRequest(RequestLine("DELETE", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder()
                    .apply {
                        with("Host", "localhost:$port")
                        with("Connection", "close")
                        with("Content-Length", body.length.toString())
                    }
                    .build(), EagerBodyReader(body.toByteArray()), null)
            }
        }
    }

    @Test
    fun `TRACE response test`() = simpleTest { port ->
        mutableListOf<RawHttpRequest>().apply {
            repeat(`test request count`) {
                this += RawHttpRequest(RequestLine("TRACE", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder()
                    .apply {
                        with("Host", "localhost:$port")
                        with("Connection", "close")
                        with("Content-Length", "0")
                    }
                    .build(), null, null)
            }
        }
    }

    @Test
    fun `PATCH response test`() = simpleTest { port ->
        mutableListOf<RawHttpRequest>().apply {
            repeat(`test request count`) {
                val body = """{ "key":"$it" }"""
                this += RawHttpRequest(RequestLine("PATCH", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder()
                    .apply {
                        with("Host", "localhost:$port")
                        with("Connection", "close")
                        with("Content-Length", body.length.toString())
                    }
                    .build(), EagerBodyReader(body.toByteArray()), null)
            }
        }
    }

    @Test
    fun `OPTIONS response test`() = simpleTest { port ->
        mutableListOf<RawHttpRequest>().apply {
            repeat(`test request count`) {
                val body = """{ "key":"$it" }"""
                this += RawHttpRequest(RequestLine("OPTIONS", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder()
                    .apply {
                        with("Host", "localhost:$port")
                        with("Connection", "close")
                        with("Content-Length", body.length.toString())
                    }
                    .build(), EagerBodyReader(body.toByteArray()), null)
            }
        }
    }

    @Test
    fun `CONNECT response test`() = simpleTest(false, false) { port ->
        mutableListOf<RawHttpRequest>().apply {
            val body = """{ "key":"0" }"""
            this += RawHttpRequest(RequestLine("CONNECT", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder()
                .apply {
                    with("Host", "localhost:$port")
                    with("Content-Length", body.length.toString())
                }
                .build(), EagerBodyReader(body.toByteArray()), null)
        }
    }

    @Test
    fun `HEAD response test`() = simpleTest(false, true) { port ->
        mutableListOf<RawHttpRequest>().apply {
            repeat(`test request count`) {
                val body = """{ "key":"$it" }"""
                this += RawHttpRequest(RequestLine("HEAD", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder()
                    .apply {
                        with("Host", "localhost:$port")
                        with("Connection", "close")
                        with("Content-Length", body.length.toString())
                    }
                    .build(), EagerBodyReader(body.toByteArray()), null)
            }
        }
    }

}