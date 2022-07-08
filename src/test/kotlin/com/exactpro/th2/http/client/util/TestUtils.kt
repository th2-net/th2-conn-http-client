package com.exactpro.th2.http.client.util

import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.conn.dirty.tcp.core.api.IChannel
import com.exactpro.th2.conn.dirty.tcp.core.api.impl.Channel
import com.exactpro.th2.conn.dirty.tcp.core.api.impl.DummyManglerFactory
import com.exactpro.th2.http.client.dirty.handler.HttpHandler
import com.exactpro.th2.http.client.dirty.handler.HttpHandlerSettings
import com.exactpro.th2.http.client.dirty.handler.state.IState
import io.netty.buffer.Unpooled
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import mu.KotlinLogging
import org.junit.jupiter.api.Assertions
import rawhttp.core.RawHttpRequest
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private val LOGGER = KotlinLogging.logger { }

fun IChannel.send(request: RawHttpRequest, metadata: Map<String, String>) = this.send(Unpooled.buffer().writeBytes(request.toString().toByteArray()) , metadata, IChannel.SendMode.HANDLE)

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
        val requests = mutableListOf<FullHttpRequest>()
        val responses = mutableListOf<FullHttpResponse>()

        override fun onResponse(response: FullHttpResponse) {
            responses.add(response)
        }

        override fun onRequest(request: FullHttpRequest) {
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
                Assertions.assertEquals(200, resultResponse.status().code())
                Assertions.assertEquals("OK", resultResponse.status().reasonPhrase())
                if (withBodyHeader) {
                    Assertions.assertEquals("plain/text", resultResponse.headers().get("Content-Type"))
                    Assertions.assertEquals(if (withBody) ServerIncluded.responseContentLength.toString() else "0", resultResponse.headers().get("Content-Length"))
                } else {
                    Assertions.assertEquals(null, resultResponse.headers().get("Content-Type"))
                    Assertions.assertEquals("0", resultResponse.headers().get("Content-Length"))
                }
                Assertions.assertEquals(if (withBody) ServerIncluded.responseContentLength else 0, resultResponse.content().writerIndex()) // --> released after use
            }
            state.requests.first().also { resultRequest ->
                Assertions.assertEquals(request.method, resultRequest.method().name())
                Assertions.assertEquals(/*http://localhost:${client.address.port}*/"/test", resultRequest.uri().toString())
                val resultRequestHeaders = resultRequest.headers()
                request.headers.asMap().forEach { (name, values) ->
                    Assertions.assertEquals(values.joinToString(", "), resultRequestHeaders.get(name))
                }
                if (request.body.isPresent) {
                    Assertions.assertEquals(request.body.get().decodeBody().size, resultRequest.content().writerIndex())
                }
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
    val request = Unpooled.buffer().writeBytes(getRequest(port).toString().toByteArray())
    val testContext = TestContext(HttpHandlerSettings())

    val state = object : IState {
        val requests = AtomicInteger(0)
        val responses = AtomicInteger(0)

        override fun onResponse(response: FullHttpResponse) {
            responses.incrementAndGet()
        }

        override fun onRequest(request: FullHttpRequest) {
            requests.incrementAndGet()
        }
    }

    val client = createClient(HttpHandler(testContext, state, testContext.settings as HttpHandlerSettings), 10, port)
    testContext.init(client)

    repeat(times) {
        client.send(request, mapOf(), IChannel.SendMode.HANDLE)
    }

    LOGGER.info { "$times requests was sent" }

    waitUntil(5000) {
        state.responses == state.requests
    }
}

fun createClient(handler: HttpHandler, corePoolSize: Int, serverPort: Int): IChannel = Channel(
    InetSocketAddress("localhost", serverPort),
    Channel.Security(),
    "alias",
    false,
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