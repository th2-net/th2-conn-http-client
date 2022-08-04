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

import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpRequest
import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpResponse
import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.DirtyRequestDecoder
import io.netty.handler.codec.DirtyResponseDecoder
import io.netty.handler.codec.http.HttpClientCodec

/**
 * @see HttpClientCodec
 */
class DirtyHttpClientCodec: AutoCloseable {
    private val requestDecoder = DirtyRequestDecoder()
    private val responseDecoder = DirtyResponseDecoder()

    private val requestChannel: EmbeddedChannel = EmbeddedChannel().apply {
        this.pipeline().addLast("", requestDecoder)
    }

    private val responseChannel: EmbeddedChannel = EmbeddedChannel().apply {
        this.pipeline().addLast("", responseDecoder)
    }

    fun onRequest(msg: ByteBuf): DirtyHttpRequest {
        requestChannel.writeInbound(msg)
        if (requestChannel.inboundMessages().isEmpty()) error("Cannot decode request for single attempt")
        return requestChannel.inboundMessages().poll() as DirtyHttpRequest
    }

    fun onResponse(msg: ByteBuf): DirtyHttpResponse? {
        responseChannel.writeInbound(msg)
        if (responseChannel.inboundMessages().size > 0) {
            return responseChannel.inboundMessages().poll() as DirtyHttpResponse
        }
        return null
    }

    override fun close() {
        requestChannel.close()
        responseChannel.close()
    }

    companion object {

    }
}
