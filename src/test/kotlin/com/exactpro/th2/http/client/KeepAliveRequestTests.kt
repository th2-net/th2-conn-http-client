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

class KeepAliveRequestTests : ServerIncluded() {

    @Test
    fun `keep alive test`() = simpleTest { port ->
        listOf(
            RawHttpRequest(RequestLine("GET", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder().with("Host", "localhost:$port").build(), EagerBodyReader("""{ "key":"value" }""".toByteArray()), null),
            RawHttpRequest(RequestLine("GET", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder()
                .apply {
                    with("Host", "localhost:$port")
                    with("Connection", "close")
                }
                .build(), EagerBodyReader("""{ "key":"value" }""".toByteArray()), null)
        )
    }

}