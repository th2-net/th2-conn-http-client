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

package com.exactpro.th2.http.client.dirty.handler.codec

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.CombinedChannelDuplexHandler
import io.netty.handler.codec.PrematureChannelClosureException
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpClientUpgradeHandler
import io.netty.handler.codec.http.HttpMessage
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectDecoder
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpRequestEncoder
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseDecoder
import io.netty.handler.codec.http.LastHttpContent
import io.netty.util.ReferenceCountUtil
import java.util.ArrayDeque
import java.util.Queue
import java.util.concurrent.atomic.AtomicLong

/**
 * @see HttpClientCodec
 */
class DirtyHttpClientCodec : CombinedChannelDuplexHandler<HttpResponseDecoder, HttpRequestEncoder>, HttpClientUpgradeHandler.SourceCodec {
    private val queue: Queue<HttpMethod> = ArrayDeque()
    private val parseHttpAfterConnectRequest: Boolean

    private var done = false
    private val requestResponseCounter = AtomicLong()
    private val failOnMissingResponse: Boolean

    constructor(maxInitialLineLength: Int = HttpObjectDecoder.DEFAULT_MAX_INITIAL_LINE_LENGTH, maxHeaderSize: Int = HttpObjectDecoder.DEFAULT_MAX_HEADER_SIZE, maxChunkSize: Int = HttpObjectDecoder.DEFAULT_MAX_CHUNK_SIZE, failOnMissingResponse: Boolean = DEFAULT_FAIL_ON_MISSING_RESPONSE, validateHeaders: Boolean = HttpObjectDecoder.DEFAULT_VALIDATE_HEADERS, parseHttpAfterConnectRequest: Boolean = DEFAULT_PARSE_HTTP_AFTER_CONNECT_REQUEST) {
        init(Decoder(maxInitialLineLength, maxHeaderSize, maxChunkSize, validateHeaders), Encoder())
        this.failOnMissingResponse = failOnMissingResponse
        this.parseHttpAfterConnectRequest = parseHttpAfterConnectRequest
    }

    constructor(maxInitialLineLength: Int, maxHeaderSize: Int, maxChunkSize: Int, failOnMissingResponse: Boolean, validateHeaders: Boolean, initialBufferSize: Int, parseHttpAfterConnectRequest: Boolean = DEFAULT_PARSE_HTTP_AFTER_CONNECT_REQUEST, allowDuplicateContentLengths: Boolean = HttpObjectDecoder.DEFAULT_ALLOW_DUPLICATE_CONTENT_LENGTHS, allowPartialChunks: Boolean = HttpObjectDecoder.DEFAULT_ALLOW_PARTIAL_CHUNKS) {
        init(Decoder(maxInitialLineLength, maxHeaderSize, maxChunkSize, validateHeaders, initialBufferSize, allowDuplicateContentLengths, allowPartialChunks), Encoder())
        this.parseHttpAfterConnectRequest = parseHttpAfterConnectRequest
        this.failOnMissingResponse = failOnMissingResponse
    }

    fun getDecoder() = this.inboundHandler()
    fun getEncoder() = this.outboundHandler()

    override fun prepareUpgradeFrom(ctx: ChannelHandlerContext) {
        (outboundHandler() as Encoder).upgraded = true
    }

    override fun upgradeFrom(ctx: ChannelHandlerContext) {
        val p = ctx.pipeline()
        p.remove(this)
    }

    var isSingleDecode: Boolean
        get() = inboundHandler()!!.isSingleDecode
        set(singleDecode) {
            inboundHandler()!!.isSingleDecode = singleDecode
        }

    private inner class Encoder : HttpRequestEncoder() {
        var upgraded = false

        override fun encode(ctx: ChannelHandlerContext, msg: Any, out: MutableList<Any>) {
            if (upgraded) {
                out.add(ReferenceCountUtil.retain(msg))
                return
            }
            if (msg is HttpRequest) {
                queue.offer(msg.method())
            }
            super.encode(ctx, msg, out)
            if (failOnMissingResponse && !done) {
                // check if the request is chunked if so do not increment
                if (msg is LastHttpContent) {
                    // increment as its the last chunk
                    requestResponseCounter.incrementAndGet()
                }
            }
        }
    }

    private inner class Decoder : HttpResponseDecoder {
        constructor(maxInitialLineLength: Int, maxHeaderSize: Int, maxChunkSize: Int, validateHeaders: Boolean) : super(maxInitialLineLength, maxHeaderSize, maxChunkSize, validateHeaders) {}
        constructor(maxInitialLineLength: Int, maxHeaderSize: Int, maxChunkSize: Int, validateHeaders: Boolean, initialBufferSize: Int, allowDuplicateContentLengths: Boolean, allowPartialChunks: Boolean) : super(maxInitialLineLength, maxHeaderSize, maxChunkSize, validateHeaders, initialBufferSize, allowDuplicateContentLengths, allowPartialChunks) {}

        override fun decode(ctx: ChannelHandlerContext, buffer: ByteBuf, out: MutableList<Any>) {
            if (done) {
                val readable = actualReadableBytes()
                if (readable == 0) return
                out.add(buffer.readBytes(readable))
            } else {
                val oldSize = out.size
                super.decode(ctx, buffer, out)
                if (failOnMissingResponse) {
                    val size = out.size
                    for (i in oldSize until size) {
                        decrement(out[i])
                    }
                }
            }
        }

        private fun decrement(msg: Any?) {
            if (msg == null) {
                return
            }

            if (msg is LastHttpContent) {
                requestResponseCounter.decrementAndGet()
            }
        }

        override fun isContentAlwaysEmpty(msg: HttpMessage): Boolean {
            val method = queue.poll()
            val statusCode = (msg as HttpResponse).status().code()
            if (statusCode in 100..199) {
                return super.isContentAlwaysEmpty(msg)
            }

            if (method != null) {
                when (method.name()[0]) {
                    'H' -> if (HttpMethod.HEAD == method) {
                        return true
                    }
                    'C' -> if (statusCode == 200) {
                        if (HttpMethod.CONNECT == method) {
                            if (!parseHttpAfterConnectRequest) {
                                done = true
                                queue.clear()
                            }
                            return true
                        }
                    }
                    else -> {}
                }
            }
            return super.isContentAlwaysEmpty(msg)
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            super.channelInactive(ctx)
            if (failOnMissingResponse) {
                val missingResponses = requestResponseCounter.get()
                if (missingResponses > 0) {
                    ctx.fireExceptionCaught(PrematureChannelClosureException("channel gone inactive with $missingResponses missing response(s)"))
                }
            }
        }
    }

    companion object {
        const val DEFAULT_FAIL_ON_MISSING_RESPONSE = false
        const val DEFAULT_PARSE_HTTP_AFTER_CONNECT_REQUEST = false
    }
}
