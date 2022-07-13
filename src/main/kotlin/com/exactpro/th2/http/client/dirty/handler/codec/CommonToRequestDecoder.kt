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
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageDecoder
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion

/**
 * Decoder from common metadata + body into full request
 */
@ChannelHandler.Sharable
class CommonToRequestDecoder(private val defaultHeaders: Map<String, List<String>> = emptyMap()): MessageToMessageDecoder<Pair<ByteBuf, MutableMap<String, String>>>() {
    override fun decode(ctx: ChannelHandlerContext, msg: Pair<ByteBuf, MutableMap<String, String>>, out: MutableList<Any>) {
        val body = msg.first
        val metadata = msg.second
        val method = metadata[METHOD_PROPERTY]?.uppercase() ?: DEFAULT_METHOD
        val uri = metadata[URI_PROPERTY] ?: DEFAULT_URI

        val fullRequest = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), uri, body)

        fullRequest.headers().let { resultHeaders ->
            defaultHeaders.forEach {
                resultHeaders.add(it.key, it.value)
            }

            metadata[CONTENT_TYPE_PROPERTY]?.run {
                resultHeaders.add(CONTENT_TYPE_HEADER, split(HEADER_VALUE_SEPARATOR))
            }

            if (CONTENT_LENGTH_HEADER !in resultHeaders) {
                resultHeaders.add(CONTENT_LENGTH_HEADER, body.readableBytes().toString())
            }
        }

        out.add(fullRequest)
    }

    companion object {

        private const val METHOD_FIELD = "method"
        private const val URI_FIELD = "uri"

        private const val METHOD_PROPERTY = METHOD_FIELD
        private const val URI_PROPERTY = URI_FIELD
        private const val CONTENT_TYPE_PROPERTY = "contentType"

        private const val DEFAULT_METHOD = "GET"
        private const val DEFAULT_URI = "/"

        private const val CONTENT_TYPE_HEADER = "Content-Type"
        private const val CONTENT_LENGTH_HEADER = "Content-Length"
        private const val HEADER_VALUE_SEPARATOR = ";"
    }
}