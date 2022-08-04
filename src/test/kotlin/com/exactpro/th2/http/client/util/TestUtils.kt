/*
 * Copyright 2022-2022 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.http.client.util

import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.conn.dirty.tcp.core.api.IChannel
import com.exactpro.th2.conn.dirty.tcp.core.api.impl.Channel
import com.exactpro.th2.conn.dirty.tcp.core.api.impl.DummyManglerFactory
import com.exactpro.th2.http.client.dirty.handler.HttpHandler
import com.exactpro.th2.http.client.dirty.handler.HttpHandlerSettings
import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpRequest
import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpResponse
import com.exactpro.th2.http.client.dirty.handler.stateapi.IState
import io.netty.buffer.Unpooled
import io.netty.channel.nio.NioEventLoopGroup
import mu.KotlinLogging
import org.junit.jupiter.api.Assertions
import rawhttp.core.RawHttpRequest
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private val LOGGER = KotlinLogging.logger { }

fun IChannel.send(request: RawHttpRequest, metadata: Map<String, String>) = this.send(Unpooled.buffer().writeBytes(request.toString().toByteArray()), metadata, IChannel.SendMode.HANDLE)

fun waitUntil(timeout: Long, step: Long = 100, check: () -> Boolean) {
    LOGGER.info {"Start waiting for positive check"}
    var fullTime = 0L
    while (!check() && timeout > fullTime) {
        fullTime+=step
        Thread.sleep(step)
    }
}

fun simpleTestSingle(port: Int, withBody: Boolean = true, withBodyHeader: Boolean = withBody, getRequest: (Int) -> RawHttpRequest) {
    simpleTest(port, withBody, withBodyHeader) { listOf(getRequest(it)) }
}

fun simpleTest(port: Int, withBody: Boolean = true, withBodyHeader: Boolean = withBody, getRequests: (Int) -> List<RawHttpRequest>) {
    val defaultHeaders = mapOf("Accept-Encoding" to listOf("gzip", "deflate"))
    val testContext = TestContext(HttpHandlerSettings().apply {
        this.defaultHeaders = defaultHeaders
    })

    val state = object : IState {
        val requests = mutableListOf<DirtyHttpRequest>()
        val responses = mutableListOf<DirtyHttpResponse>()

        override fun onResponse(response: DirtyHttpResponse) {
            responses.add(response)
        }

        override fun onRequest(request: DirtyHttpRequest) {
            requests.add(request)
        }
    }

    val client = createClient(HttpHandler(testContext, state, testContext.settings as HttpHandlerSettings), 10, port)
    testContext.init(client)

    val requests = getRequests(port)
    try {
        requests.forEachIndexed { index, request ->
            if (!client.isOpen) client.open()

            client.send(request, mapOf())

            waitUntil(2500) {
                state.responses.isNotEmpty()
            }
            Assertions.assertEquals(1, state.requests.size)
            Assertions.assertEquals(1, state.responses.size)
            state.responses.first().also { resultResponse ->
                Assertions.assertEquals(200, resultResponse.code)
                Assertions.assertEquals("OK", resultResponse.reason)
                if (withBodyHeader) {
                    Assertions.assertEquals("plain/text", resultResponse.headers["Content-Type"])
                    Assertions.assertEquals(if (withBody) ServerIncluded.responseContentLength.toString() else "0", resultResponse.headers["Content-Length"])
                } else {
                    Assertions.assertEquals(null, resultResponse.headers["Content-Type"])
                    Assertions.assertEquals("0", resultResponse.headers["Content-Length"])
                }
//                Assertions.assertEquals(if (withBody) ServerIncluded.responseContentLength else 0, resultResponse.content().writerIndex()) // --> released after use
            }
            state.requests.first().also { resultRequest ->
                Assertions.assertEquals(request.method, resultRequest.method.name())
                Assertions.assertEquals(/*http://localhost:${client.address.port}*/"/test", resultRequest.url)
                // Buffer is freed
//                request.headers.forEach { name, values ->
//                    Assertions.assertEquals(values, resultRequest.headers[name]) {name}
//                }
//                if (request.body.isPresent) {
//                    Assertions.assertEquals(request.body.get().decodeBody().size, resultRequest.content().writerIndex())
//                }
            }
            if (request.headers.getFirst("Connection").orElse("") == "close") {
                Assertions.assertTrue(!client.isOpen)
            }

            state.requests.clear()
            state.responses.clear()

            LOGGER.debug { "TEST [${request.method}] [${index + 1}]: PASSED" }
        }
    } finally {
        client.close()
    }
}

fun stressTest(times: Int, port: Int, getRequest: (Int) -> RawHttpRequest) {
    val testContext = TestContext(HttpHandlerSettings().apply {
        this.defaultHeaders = mapOf()
    })

    val state = object : IState {
        val requests = AtomicInteger(0)
        val responses = AtomicInteger(0)
        override fun onResponse(response: DirtyHttpResponse) { responses.incrementAndGet() }
        override fun onRequest(request: DirtyHttpRequest) { requests.incrementAndGet() }
    }

    val client = createClient(HttpHandler(testContext, state, testContext.settings as HttpHandlerSettings), 10, port)
    testContext.init(client)

    val request = getRequest(port).toString().toByteArray()

    try {
        repeat(times) {
            if (!client.isOpen) client.open()
            client.send(Unpooled.buffer().writeBytes(request), mapOf(), IChannel.SendMode.HANDLE)
        }

        waitUntil(10000) {
            state.responses.get() == times
        }

        LOGGER.info { "$times requests was sent" }

        Assertions.assertEquals(times, state.requests.get()) {"Requests count must be exactly: $times; Response count: ${state.responses.get()}"}
        Assertions.assertEquals(state.requests.get(), state.responses.get()) {"Requests and response count must be same: (req) ${state.requests.get()} != ${state.responses.get()} (res)"}
    } finally {
        client.close()
    }

}

fun createClient(handler: HttpHandler, corePoolSize: Int, serverPort: Int, autoReconnect: Boolean = false): IChannel = Channel(
    InetSocketAddress("localhost", serverPort),
    Channel.Security(),
    "alias",
    autoReconnect,
    5000,
    1000,
    false,
    handler,
    DummyManglerFactory.DummyMangler,
    onEvent = {},
    onMessage = { },
    Executors.newScheduledThreadPool(corePoolSize),
    NioEventLoopGroup(corePoolSize, Executors.newScheduledThreadPool(corePoolSize)),
    EventID.getDefaultInstance()
)