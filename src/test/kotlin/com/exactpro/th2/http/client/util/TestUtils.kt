package com.exactpro.th2.http.client.util

import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.conn.dirty.tcp.core.TaskSequencePool
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.mock
import rawhttp.core.RawHttpRequest
import java.net.InetSocketAddress
import java.util.concurrent.Executors

private val LOGGER = KotlinLogging.logger { }

fun IChannel.send(request: RawHttpRequest, metadata: Map<String, String>) = this.send(Unpooled.buffer().writeBytes(request.toString().toByteArray()) , metadata, IChannel.SendMode.HANDLE)

fun waitUntil(timeout: Long, step: Long = 100, check: () -> Boolean) {
    var fullTime = 0L
    while (!check() && timeout > fullTime) {
        fullTime+=step
        Thread.sleep(step)
    }
}

fun simpleTestSingle(port: Int, withBody: Boolean = true, withBodyHeader: Boolean = withBody, getRequest: (Int) -> RawHttpRequest) {
    simpleTest(port, withBody, withBodyHeader) { port -> listOf(getRequest(port)) }
}

fun simpleTest(port: Int, withBody: Boolean = true, withBodyHeader: Boolean = withBody, getRequests: (Int) -> List<RawHttpRequest>) {
    val defaultHeaders = mapOf("Accept-Encoding" to "gzip, deflate")
    val testContext = TestContext(HttpHandlerSettings().apply {
        this.defaultHeaders = defaultHeaders
    })
    val handler = mock<IState>()

    val client = createClient(HttpHandler(testContext, handler, testContext.settings as HttpHandlerSettings), 10, port)
    testContext.init(client)

    val requests = getRequests(port)
    try {
        requests.forEachIndexed { index, request ->
            val requestCaptor = argumentCaptor<FullHttpRequest>()
            val responseCaptor = argumentCaptor<FullHttpResponse>()

            doNothing().`when`(handler).onResponse(responseCaptor.capture())
            doNothing().`when`(handler).onRequest(requestCaptor.capture())

            client.send(request, mapOf())

            waitUntil(2500) {
                responseCaptor.allValues.isNotEmpty()
            }
            Assertions.assertEquals(1, requestCaptor.allValues.size)
            Assertions.assertEquals(1, responseCaptor.allValues.size)
            responseCaptor.firstValue.also { resultResponse ->
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
            requestCaptor.firstValue.also { resultRequest ->
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
            LOGGER.debug { "TEST [${request.method}] [${index + 1}]: PASSED" }
        }
    } finally {
        client.close()
    }
}

fun createClient(handler: HttpHandler, corePoolSize: Int, serverPort: Int): IChannel = Channel(
    InetSocketAddress("localhost", serverPort),
    false,
    "alias",
    1000,
    handler,
    DummyManglerFactory.DummyMangler,
    onEvent = { _, _ -> },
    onMessage = { },
    Executors.newScheduledThreadPool(corePoolSize),
    NioEventLoopGroup(corePoolSize, Executors.newScheduledThreadPool(corePoolSize)),
    TaskSequencePool(Executors.newScheduledThreadPool(corePoolSize)),
    EventID.getDefaultInstance()
)