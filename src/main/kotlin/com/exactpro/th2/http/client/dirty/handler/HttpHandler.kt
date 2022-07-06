package com.exactpro.th2.http.client.dirty.handler

import com.exactpro.th2.conn.dirty.tcp.core.api.IContext
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandler
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings
import com.exactpro.th2.http.client.dirty.handler.state.IState
import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION
import io.netty.handler.codec.http.HttpMessage
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http.HttpRequestEncoder
import mu.KotlinLogging
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

open class HttpHandler(private val context: IContext<IProtocolHandlerSettings>, private val state: IState, private val settings: HttpHandlerSettings): IProtocolHandler {

    private val requestAggregator = HttpObjectAggregator(DEFAULT_MAX_LENGTH_AGGREGATOR)
    private val responseAggregator = HttpObjectAggregator(DEFAULT_MAX_LENGTH_AGGREGATOR)

    private val requestDecoder = HttpRequestDecoder().apply {
        (this as ByteToMessageDecoder).setCumulator { _, cumulation, `in` ->
            cumulation.release()
            `in`
        }
    }
    private val requestEncoder = HttpRequestEncoder()
    private val httpClientCodec = HttpClientCodec()

    private val httpMode = AtomicReference(HttpMode.DEFAULT)
    private val lastMethod = AtomicReference<HttpMethod?>(null)

    private var isLastResponse = AtomicBoolean(false)


    private val httpClientChannel: EmbeddedChannel = EmbeddedChannel().apply {
        this.pipeline().addLast("client", httpClientCodec).addLast("aggregator", responseAggregator)
    }

    private val requestChannel: EmbeddedChannel = EmbeddedChannel().apply {
        this.pipeline().addLast("decoder", requestDecoder).addLast("aggregator", requestAggregator).addLast("encoder", requestEncoder)
    }

    override fun onIncoming(message: ByteBuf): Map<String, String> {
        when (val mode = httpMode.get()) {
            HttpMode.DEFAULT -> {
                val response = httpClientChannel.readInbound<FullHttpResponse>()
                if (response.decoderResult().isFailure) {
                    throw response.decoderResult().cause()
                }
                LOGGER.info { "Received response: $response" }
                when {
                    isLastResponse.get() || response.status().code() >= 400 -> context.channel.close()
                    response.keepAlive() -> Unit
                    else -> context.channel.close() // all else are closing cases
                }

                when(lastMethod.get()) {
                    HttpMethod.CONNECT -> if (response.status().code() == 200) httpMode.set(HttpMode.CONNECT)
                }

                state.onResponse(response)
                response.content().release()
            }
            HttpMode.CONNECT -> LOGGER.trace { "$mode: Received data passing as tcp package" }
            else -> error("Unsupported http mode: $mode")
        }

        return mutableMapOf()
    }

    override fun onOutgoing(message: ByteBuf, metadata: MutableMap<String, String>) {
        when (val mode = httpMode.get()) {
            HttpMode.DEFAULT -> {
                val newMessage = handleRequest(message) { request ->
                    isLastResponse.set(!request.keepAlive())

                    request.headers().let { headers ->
                        settings.defaultHeaders.forEach {
                            if (!headers.contains(it.key)) {
                                headers.add(it.key, it.value)
                            }
                        }
                    }

                    lastMethod.set(request.method())

                    state.onRequest(request)
                }

                if (newMessage.writerIndex() != message.writerIndex()) {
                    message.clear().writeBytes(newMessage)
                }
            }
            HttpMode.CONNECT -> LOGGER.trace { "$mode: Sending data passing as tcp package" }
            else -> error("Unsupported http mode: $mode")
        }
    }

    override fun onReceive(buffer: ByteBuf) = if (handleResponseParts(buffer)) {
        buffer.retainedDuplicate().readerIndex(0)
    } else {
        null
    }

    private fun handleResponseParts(buffer: ByteBuf): Boolean {
        httpClientChannel.writeInbound(buffer.readBytes(buffer.writerIndex()-buffer.readerIndex()))
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
        state.onClose()
        if (isLastResponse.get() || lastMethod.get() == HttpMethod.CONNECT) {
            LOGGER.debug { "Closing channel due last response" }
            this.context.channel.close()
        }
    }

    override fun close() {
        state.close()
        httpClientChannel.close()
        requestChannel.close()
    }

    override fun onOpen() {
        state.onOpen()
    }

    companion object {
        private val LOGGER = KotlinLogging.logger { this::class.java.simpleName }
        const val DEFAULT_MAX_LENGTH_AGGREGATOR = 65536
    }

    private enum class HttpMode {
        CONNECT,
        DEFAULT
    }
}