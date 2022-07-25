package com.exactpro.th2.http.client.dirty.handler.codec

import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpBody
import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpHeaders
import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpMethod
import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpRequest
import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpURL
import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpVersion
import com.exactpro.th2.http.client.dirty.handler.parsers.HeaderParser
import com.exactpro.th2.http.client.dirty.handler.parsers.LineParser
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion

class DirtyRequestDecoder {

    private val startLineParser: LineParser = LineParser()
    private val headerParser: HeaderParser = HeaderParser()

    fun decodeSingle(buffer: ByteBuf): DirtyHttpRequest? {
        val startLine = startLineParser.parse(buffer)
        val startOfHeaders = buffer.readerIndex()
        val headers = headerParser.parse(buffer)
        val endOfHeaders = buffer.readerIndex()
        val body = DirtyHttpBody(buffer.readerIndex(), buffer)

        if (startLine.size < 3) return null
        val method = startLine[0].let { DirtyHttpMethod(it.second, HttpMethod.valueOf(it.first)) }
        val url = startLine[1].let { DirtyHttpURL(it.second, it.first) }
        val version = startLine[2].let { DirtyHttpVersion(it.second, HttpVersion.valueOf(it.first)) }
        val headerContainer = DirtyHttpHeaders(startOfHeaders, endOfHeaders-startOfHeaders, buffer, headers)

        return DirtyHttpRequest(method, url, version, body, headerContainer, buffer)
    }
}