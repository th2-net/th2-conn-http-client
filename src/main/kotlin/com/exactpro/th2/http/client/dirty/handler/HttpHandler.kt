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
import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpMessage
import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpResponse
import com.exactpro.th2.http.client.dirty.handler.stateapi.IState
import com.google.auto.service.AutoService
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.DirtyRequestDecoder
import io.netty.handler.codec.http.HttpMethod
import mu.KotlinLogging
import java.lang.Exception
import java.util.LinkedList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@AutoService(IProtocolHandler::class)
open class HttpHandler(private val context: IContext<IProtocolHandlerSettings>, private val state: IState, private val settings: HttpHandlerSettings): IProtocolHandler {
    private lateinit var hostValue: String

    private val responseOutputQueue = ConcurrentLinkedQueue<DirtyHttpResponse>()

    private val requestDecoder = DirtyRequestDecoder()
    private val httpClientCodec = DirtyHttpClientCodec()

    private val httpMode = AtomicReference(HttpMode.DEFAULT)
    private val lastMethod = AtomicReference<HttpMethod?>(null)

    private var isLastResponse = AtomicBoolean(false)

    private val httpMetadataQueue = LinkedList<Map<String, String>>()

    override fun onOutgoing(message: ByteBuf, metadata: MutableMap<String, String>) {
        try {
            when (val mode = httpMode.get()) {
                HttpMode.DEFAULT -> {
                    requestDecoder.decodeSingle(message)?.let { request ->
                        isLastResponse.set(!request.isKeepAlive())
                        settings.defaultHeaders.forEach {
                            if (!request.headers.contains(it.key)){
                                request.headers[it.key] = it.value.joinToString(",")
                            }
                        }
                        if (!request.headers.contains(HOST)){
                            request.headers[HOST] = hostValue
                        }
                        lastMethod.set(request.method)
                        state.onRequest(request)
                        LOGGER.debug { "Sending request: $request" }
                    }
                    httpMetadataQueue.push(metadata)
                }
                HttpMode.CONNECT -> LOGGER.trace { "$mode: Sending data passing as tcp package" }
                else -> error("Unsupported http mode: $mode")
            }
        } catch (e: Exception) {
            LOGGER.error(e) { "Cannot handle request" }
        } finally {
            message.readerIndex(0)
        }
    }

    override fun onIncoming(message: ByteBuf): Map<String, String> {
        when (val mode = httpMode.get()) {
            HttpMode.DEFAULT -> {
                val response = responseOutputQueue.poll() as DirtyHttpResponse
                if (response.decoderResult.isFailure) {
                    throw response.decoderResult.cause()
                }
                LOGGER.debug { "Received response: $response" }
                when {
                    isLastResponse.get() || response.code >= 400 -> context.channel.close()
                    response.isKeepAlive() -> Unit
                    else -> context.channel.close() // all else are closing cases
                }

                when(lastMethod.get()) {
                    HttpMethod.CONNECT -> if (response.code == 200) httpMode.set(HttpMode.CONNECT)
                }
                state.onResponse(response)
                return httpMetadataQueue.removeFirst()
            }
            HttpMode.CONNECT -> LOGGER.trace { "$mode: Received data passing as tcp package" }
            else -> error("Unsupported http mode: $mode")
        }

        return emptyMap()
    }

    override fun onReceive(message: ByteBuf): ByteBuf? {
        if (httpMode.get() == HttpMode.CONNECT) return message
        return httpClientCodec.onResponse(message)?.let {
            LOGGER.debug { "Response message was decoded" }
            responseOutputQueue.offer(it)
            it.reference
        }
    }

    private fun DirtyHttpMessage.isKeepAlive(): Boolean {
        return version.minorVersion() == 1 && !(this.headers[CONNECTION]?.equals("close", true) ?: false) || version.minorVersion() == 0 && this.headers[CONNECTION]?.equals("keep-alive", true) ?: false
    }

    override fun onClose() {
        state.onClose()
        if (isLastResponse.get() || lastMethod.get() == HttpMethod.CONNECT) {
            LOGGER.debug { "Closing channel due last request/response" }
        }
    }

    override fun close() {
        state.close()
        httpClientCodec.close()
    }

    override fun onOpen() {
        hostValue = context.channel.address.let { "${it.hostString}:${it.port}" }
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