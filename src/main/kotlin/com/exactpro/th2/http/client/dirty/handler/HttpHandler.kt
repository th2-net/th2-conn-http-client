package com.exactpro.th2.http.client.dirty.handler

import com.exactpro.th2.conn.dirty.tcp.core.api.IContext
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandler
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings
import com.exactpro.th2.http.client.dirty.handler.codec.Th2HttpResponseDecoder
import com.exactpro.th2.http.client.dirty.handler.state.IStateManager
import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION
import io.netty.handler.codec.http.HttpMessage
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http.HttpRequestEncoder
import mu.KotlinLogging
import java.nio.charset.Charset

open class HttpHandler(private val context: IContext<IProtocolHandlerSettings>, private val clientHandler: IStateManager): IProtocolHandler {

    private val requestAggregator = HttpObjectAggregator(DEFAULT_MAX_LENGTH_AGGREGATOR)
    private val responseAggregator = HttpObjectAggregator(DEFAULT_MAX_LENGTH_AGGREGATOR)

    private val responseDecoder = Th2HttpResponseDecoder()
    private val framingOutput: MutableList<Any> = mutableListOf()

    private val requestDecoder = HttpRequestDecoder().apply {
        (this as ByteToMessageDecoder).setCumulator { _, cumulation, `in` ->
            cumulation.release()
            `in`
        }
    }
    private val requestEncoder = HttpRequestEncoder()
    private val httpClientCodec = HttpClientCodec()
    private var shouldCloseChannel = false

    private val httpClientChannel: EmbeddedChannel = EmbeddedChannel().apply {
        this.pipeline().addLast("client", httpClientCodec).addLast("aggregator", responseAggregator)
    }

    private val requestChannel: EmbeddedChannel = EmbeddedChannel().apply {
        this.pipeline().addLast("decoder", requestDecoder).addLast("aggregator", requestAggregator).addLast("encoder", requestEncoder)
    }
    private val settings: HttpHandlerSettings = context.settings as HttpHandlerSettings

    override fun onIncoming(message: ByteBuf): Map<String, String> {
        val response = httpClientChannel.readInbound<FullHttpResponse>()
        if (response.decoderResult.isFailure) {
            throw error(response.decoderResult.cause().message ?: "Error during response decode")
        }
        LOGGER.info { "Received response: $response" }
        response.headers().let { headers ->
            when {
                shouldCloseChannel -> context.channel.close()
                response.keepAlive() -> Unit
                else -> context.channel.close() // all else are closing cases
            }
        }
        clientHandler.onResponse(response)
        response.content().release()
        return mutableMapOf()
    }

    override fun onOutgoing(message: ByteBuf, metadata: MutableMap<String, String>) {
        LOGGER.debug { "\n------------> Request: " + message.toString(Charset.defaultCharset()) }

        val newMessage = handleRequest(message) { request ->
            LOGGER.info { "Sending request: $request" }
            shouldCloseChannel = !request.keepAlive()

            request.headers().let { headers ->
                settings.defaultHeaders.forEach {
                    if (!headers.contains(it.key)) {
                        headers.add(it.key, it.value)
                    }
                }
            }
            clientHandler.onRequest(request)
        }
        LOGGER.debug { "\n------------> Final Request: " + newMessage.toString(Charset.defaultCharset()) }

        if (newMessage.writerIndex() != message.writerIndex()) {
            message.clear().writeBytes(newMessage)
        }
    }

    override fun onReceive(buffer: ByteBuf) = if (handleResponseParts(buffer)) {
        try {
            buffer.retainedDuplicate().readerIndex(0)
        } finally {
            //buffer.release()
        }
    } else {
        null
    }

    private fun handleResponseParts(buffer: ByteBuf): Boolean {
        val startIdx = responseDecoder.decode0(buffer, framingOutput)
        if(startIdx >= 0 || !context.channel.isOpen && framingOutput.isNotEmpty()) {
            httpClientChannel.writeInbound(buffer.readerIndex(0).retain())
        }
        return httpClientChannel.inboundMessages().size > 0
    }

    private fun handleRequest(message: ByteBuf, handler: (FullHttpRequest) -> Unit): ByteBuf = runCatching {
        requestChannel.run {
            check(writeInbound(message.retain())) { "Decoding request did not produce any results" }
            check(inboundMessages().size == 1) { "Expected 1 result, but got: ${inboundMessages().size}" }
            readInbound<FullHttpRequest>().apply(handler)
        }.let { request ->
            check(httpClientChannel.writeOutbound(request)) { "Encoding by client codec did not produce any results" }
            check(httpClientChannel.outboundMessages().size == 1) { "Expected 1 result, but got: ${httpClientChannel.outboundMessages().size}" }
            httpClientChannel.readOutbound<ByteBuf>()
        }
    }.getOrElse {
        httpClientChannel.releaseOutbound()
        throw IllegalStateException("Failed to handle message: ${message.resetReaderIndex().readCharSequence(message.writerIndex(), Charset.defaultCharset())}", it)
    }

    private fun HttpMessage.keepAlive() = headers().let { headers ->
        protocolVersion().minorVersion() == 1 && !(headers.get(CONNECTION)?.equals("close", true) ?: false) || protocolVersion().minorVersion() == 0 && headers.get(CONNECTION)?.equals("keep-alive", true) ?: false
    }

    override fun onClose() {
        if (shouldCloseChannel) {
            LOGGER.debug { "Closing channel due last request" }
            this.context.channel.close()
        }
    }

    override fun close() {
        httpClientChannel.close()
        requestChannel.close()
    }


    companion object {
        private val LOGGER = KotlinLogging.logger { this::class.java.simpleName }
        const val DEFAULT_MAX_LENGTH_AGGREGATOR = 65536
    }
}