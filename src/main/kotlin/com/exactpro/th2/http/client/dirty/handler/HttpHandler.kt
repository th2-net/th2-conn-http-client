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

package com.exactpro.th2.http.client.dirty.handler

import com.exactpro.th2.conn.dirty.tcp.core.api.IContext
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandler
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings
import com.exactpro.th2.http.client.dirty.handler.codec.DirtyHttpClientCodec
import com.exactpro.th2.http.client.dirty.handler.state.IState
import com.google.auto.service.AutoService
import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpMessage
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http.HttpRequestEncoder
import mu.KotlinLogging
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@AutoService(IProtocolHandler::class)
open class HttpHandler(private val context: IContext<IProtocolHandlerSettings>, private val state: IState, private val settings: HttpHandlerSettings): IProtocolHandler {

    private val requestAggregator = HttpObjectAggregator(DEFAULT_MAX_LENGTH_AGGREGATOR)
    private val responseAggregator = HttpObjectAggregator(DEFAULT_MAX_LENGTH_AGGREGATOR)
    private val responseOutputQueue = ConcurrentLinkedQueue<FullHttpResponse>()

    private val requestDecoder = HttpRequestDecoder().apply {
        (this as ByteToMessageDecoder).setCumulator { _, cumulation, `in` ->
            cumulation.release()
            `in`
        }
    }
    private val requestEncoder = HttpRequestEncoder()
    private val httpClientCodec = DirtyHttpClientCodec().apply {
        decoder.setCumulator { _, cumulation, `in` ->
            cumulation.release()
            `in`.retain()
        }
        isSingleDecode = true
    }

    private val httpMode = AtomicReference(HttpMode.DEFAULT)
    private val lastMethod = AtomicReference<HttpMethod?>(null)

    private var isLastResponse = AtomicBoolean(false)


    private val httpClientChannel: EmbeddedChannel = EmbeddedChannel().apply {
        this.pipeline().addLast("client", httpClientCodec).addLast("aggregator", responseAggregator)
    }

    private val requestChannel: EmbeddedChannel = EmbeddedChannel().apply {
        this.pipeline().addLast("decoder", requestDecoder).addLast("aggregator", requestAggregator).addLast("encoder", requestEncoder)
    }

    override fun onOutgoing(message: ByteBuf, metadata: MutableMap<String, String>) {
        when (val mode = httpMode.get()) {
            HttpMode.DEFAULT -> {
                val newMessage = handleRequest(message) { request ->
                    isLastResponse.set(!request.isKeepAlive())

                    request.headers().let { headers ->
                        settings.defaultHeaders.forEach {
                            if (!headers.contains(it.key)) {
                                headers.add(it.key, it.value)
                            }
                        }
                        if (HOST !in headers) {
                            headers.add(HOST, context.channel.address.let { "${it.hostString}:${it.port}" })
                        }
                    }

                    lastMethod.set(request.method())

                    state.onRequest(request)
                    LOGGER.debug { "Sending request request: $request" }
                }

                if (newMessage.writerIndex() != message.writerIndex()) {
                    message.clear().writeBytes(newMessage)
                } else {
                    message.resetReaderIndex()
                }
            }
            HttpMode.CONNECT -> LOGGER.trace { "$mode: Sending data passing as tcp package" }
            else -> error("Unsupported http mode: $mode")
        }
    }

    override fun onIncoming(message: ByteBuf): Map<String, String> {
        when (val mode = httpMode.get()) {
            HttpMode.DEFAULT -> {
                val response = responseOutputQueue.poll() as FullHttpResponse
                if (response.decoderResult().isFailure) {
                    throw response.decoderResult().cause()
                }
                LOGGER.debug { "Received response: $response" }
                when {
                    isLastResponse.get() || response.status().code() >= 400 -> context.channel.close()
                    response.isKeepAlive() -> Unit
                    else -> context.channel.close() // all else are closing cases
                }

                when(lastMethod.get()) {
                    HttpMethod.CONNECT -> if (response.status().code() == 200) httpMode.set(HttpMode.CONNECT)
                }

                state.onResponse(response)
                response.release()
            }
            HttpMode.CONNECT -> LOGGER.trace { "$mode: Received data passing as tcp package" }
            else -> error("Unsupported http mode: $mode")
        }

        return mutableMapOf()
    }

    override fun onReceive(buffer: ByteBuf): ByteBuf? {
        if (httpMode.get() == HttpMode.CONNECT) return buffer
        return handleResponseParts(buffer)?.let {
            LOGGER.debug { "Message found" }
            responseOutputQueue.offer(it)
            buffer.retainedDuplicate().readerIndex(0)
        }
    }

    private fun handleResponseParts(buffer: ByteBuf): FullHttpResponse? {
        //while (buffer.isReadable && !httpClientChannel.writeInbound(buffer.retainedSliceLine() ?: error("Part is unreadable"))) {}
        var oldReaderIndex = buffer.readerIndex()
        while(buffer.isReadable) {
            httpClientChannel.writeInbound(buffer.retain())
            if (buffer.readerIndex() == oldReaderIndex || httpClientChannel.inboundMessages().size > 0) break
            oldReaderIndex = buffer.readerIndex()
        }
        if (httpClientChannel.inboundMessages().size > 0) {
            return httpClientChannel.inboundMessages().poll() as FullHttpResponse
        }
        return null
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

    private fun HttpMessage.isKeepAlive() = headers().let { headers ->
        protocolVersion().minorVersion() == 1 && !(headers.get(CONNECTION)?.equals("close", true) ?: false) || protocolVersion().minorVersion() == 0 && headers.get(CONNECTION)?.equals("keep-alive", true) ?: false
    }

    override fun onClose() {
        state.onClose()
        if (isLastResponse.get() || lastMethod.get() == HttpMethod.CONNECT) {
            LOGGER.debug { "Closing channel due last request/response" }
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
        const val CONNECTION = "Connection"
        const val HOST = "Host"
    }

    private enum class HttpMode {
        CONNECT,
        DEFAULT
    }
}