package com.exactpro.th2.http.client

import com.exactpro.th2.http.client.util.ServerIncluded
import com.exactpro.th2.http.client.util.stressTest
import mu.KotlinLogging
import org.junit.jupiter.api.Test
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RequestLine
import rawhttp.core.body.EagerBodyReader
import java.net.URI

class StressTests: ServerIncluded() {

    @Test
    fun `simple stress test`() {
        stressTest(2000, serverPort) { port ->
            val body = """{ "key":"value" }"""
            RawHttpRequest(RequestLine("GET", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder()
                .apply {
                    with("Host", "localhost:$port")
                    with("Content-Length", body.length.toString())
                }
                .build(), EagerBodyReader(body.toByteArray()), null)
        }
    }
}