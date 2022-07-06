package com.exactpro.th2.http.client.util

import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.conn.dirty.tcp.core.TaskSequencePool
import com.exactpro.th2.conn.dirty.tcp.core.api.IChannel
import com.exactpro.th2.conn.dirty.tcp.core.api.impl.Channel
import com.exactpro.th2.conn.dirty.tcp.core.api.impl.DummyManglerFactory
import com.exactpro.th2.http.client.dirty.handler.HttpHandler
import com.exactpro.th2.http.server.HttpServer
import com.exactpro.th2.http.server.Main
import com.exactpro.th2.http.server.api.IStateManager
import com.exactpro.th2.http.server.api.IStateManagerSettings
import com.exactpro.th2.http.server.api.impl.BasicStateManager
import com.exactpro.th2.http.server.options.Th2ServerOptions
import com.exactpro.th2.http.server.util.LinkedData
import io.netty.channel.nio.NioEventLoopGroup
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import rawhttp.core.EagerHttpResponse
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.RequestLine
import rawhttp.core.StartLine
import rawhttp.core.StatusLine
import rawhttp.core.body.BodyReader
import rawhttp.core.body.LazyBodyReader
import rawhttp.core.errors.InvalidHttpResponse
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

abstract class ServerIncluded {
    companion object {
        //private val LOGGER = KotlinLogging.logger { this::class.java.simpleName }

        private const val serverPort = 25565
        private const val corePoolSize = 12
        private const val responseBody = """{ "id" : 901, "name" : { "first":"Tom", "middle":"and", "last":"Jerry" }, "phones" : [ {"type" : "home", "number" : "1233333" }, {"type" : "work", "number" : "264444" }], "lazy" : false, "married" : null }"""

        private val rawHttp = RawHttp()
        private val stateManager: IStateManager = object: BasicStateManager() {
            override val settingsClass: Class<out IStateManagerSettings> = IStateManagerSettings::class.java

            override fun onRequest(request: RawHttpRequest, uuid: String) {
                val stringResponse = when(request.method) {
                    "HEAD" -> createResponse(false, true)
                    "CONNECT" -> createResponse(false, false)
                    else -> createResponse()
                }
                //LOGGER.debug { "Response string created: \n$stringResponse" }
                val response = EagerHttpResponse.from(rawHttp.parseResponse(stringResponse, LinkedData(uuid, EventID.getDefaultInstance(), null)))

                server.handleResponse(response)
            }
        }
        private lateinit var server: HttpServer

        private fun createResponse(withBody: Boolean = true, withBodyHeaders: Boolean = withBody): String {
            return """HTTP/1.1 200 OK ${if (withBodyHeaders) "\nContent-Type: plain/text\nContent-Length:  ${if (withBody) responseContentLength else "0"}" else "\nContent-Length: 0"}${if (withBody) "\n\n$responseBody" else ""}""".trimIndent()
        }

        fun RawHttp.parseResponse(response: String, linkedData: LinkedData): RawHttpResponse<LinkedData> {
            return try {
                parseResponse(ByteArrayInputStream(response.toByteArray(StandardCharsets.UTF_8)), null, linkedData)
            } catch (e: IOException) {
                // IOException should be impossible
                throw RuntimeException(e)
            }
        }

        private fun RawHttp.parseResponse(inputStream: InputStream, requestLine: RequestLine?, linkedData: LinkedData): RawHttpResponse<LinkedData> {
            val statusLine: StatusLine = metadataParser.parseStatusLine(inputStream)
            val headers: RawHttpHeaders = metadataParser.parseHeaders(inputStream) { message: String?, lineNumber: Int ->  // add 1 to the line number to correct for the start-line
                InvalidHttpResponse(message, lineNumber + 1)
            }
            val bodyReader: BodyReader? = if (RawHttp.responseHasBody(statusLine, requestLine)) createBodyReader(inputStream, statusLine, headers) else null
            return RawHttpResponse(linkedData, null, statusLine, headers, bodyReader)
        }

        private fun RawHttp.createBodyReader(inputStream: InputStream, startLine: StartLine, headers: RawHttpHeaders): BodyReader {
            return LazyBodyReader(getFramedBody(startLine, headers), inputStream)
        }

        val responseContentLength: Int
            get() = responseBody.length

        @BeforeAll
        @JvmStatic
        fun setUp() {
            server = HttpServer(Th2ServerOptions(Main.Companion.MicroserviceSettings(port = serverPort, sessionAlias = "test_alias", customSettings = null), stateManager = stateManager, onEvent = {_,_ -> ""}, onRequest = {}, onResponse = {}), 1000, 2000)
            server.start()
        }

        @AfterAll
        @JvmStatic
        fun finish() {
            server.stop()
        }

        fun createClient(handler: HttpHandler): IChannel = Channel(
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
    }
}