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


package io.netty.handler.codec

import com.exactpro.th2.http.client.dirty.handler.data.pointers.BodyPointer
import com.exactpro.th2.http.client.dirty.handler.data.pointers.HeadersPointer
import com.exactpro.th2.http.client.dirty.handler.data.pointers.MethodPointer
import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpRequest
import com.exactpro.th2.http.client.dirty.handler.data.pointers.StringPointer
import com.exactpro.th2.http.client.dirty.handler.data.pointers.VersionPointer
import com.exactpro.th2.http.client.dirty.handler.parsers.HeaderParser
import com.exactpro.th2.http.client.dirty.handler.parsers.StartLineParser
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion

class DirtyRequestDecoder: ByteToMessageDecoder() {

    private val startLineParser: StartLineParser = StartLineParser()
    private val headerParser: HeaderParser = HeaderParser()

    fun decodeSingle(buffer: ByteBuf): DirtyHttpRequest? {
        if (!startLineParser.parseLine(buffer)) return null
        val startLine = startLineParser.lineParts
        startLineParser.reset()
        if (startLine.size < 3) return null

        val startOfHeaders = buffer.readerIndex()
        if (!headerParser.parseHeaders(buffer)) return null
        val headers = headerParser.getHeaders()
        headerParser.reset()
        val endOfHeaders = buffer.readerIndex()
        val body = BodyPointer(buffer.readerIndex(), buffer, buffer.writerIndex()-buffer.readerIndex())

        val method = startLine[0].let { MethodPointer(it.second, HttpMethod.valueOf(it.first)) }
        val url = startLine[1].let { StringPointer(it.second, it.first) }
        val version = startLine[2].let { VersionPointer(it.second, HttpVersion.valueOf(it.first)) }
        val headerContainer = HeadersPointer(startOfHeaders, endOfHeaders-startOfHeaders, buffer, headers)

        return DirtyHttpRequest(method, url, version, body, headerContainer, buffer)
    }

    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        decodeSingle(msg)?.let {
            out.add(it)
        }
    }
}