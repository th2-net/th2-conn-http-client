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


package com.exactpro.th2.http.client.dirty.handler.data

import com.exactpro.th2.conn.dirty.tcp.core.util.replace
import com.exactpro.th2.http.client.dirty.handler.data.pointers.BodyPointer
import com.exactpro.th2.http.client.dirty.handler.data.pointers.HeadersPointer
import com.exactpro.th2.http.client.dirty.handler.data.pointers.VersionPointer
import com.exactpro.th2.http.client.dirty.handler.data.pointers.IntPointer
import com.exactpro.th2.http.client.dirty.handler.data.pointers.StringPointer
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.DecoderResult
import io.netty.handler.codec.http.HttpVersion

class DirtyHttpResponse(httpVersion: VersionPointer, private val httpCode: IntPointer, private val httpReason: StringPointer, httpBody: BodyPointer, headers: HeadersPointer, reference: ByteBuf, decoderResult: DecoderResult = DecoderResult.SUCCESS): DirtyHttpMessage(httpVersion, headers, httpBody, reference, decoderResult) {

    var code: Int
        get() = httpCode.value
        set(value) = this.httpCode.let {
            reference.replace(it.position, reference.writerIndex(), value.toString())
            it.value = value
            settle()
        }

    var reason: String
        get() = httpReason.value
        set(value) = this.httpReason.let {
            reference.replace(it.position, reference.writerIndex(), value)
            it.value = value
            settle()
        }

    override fun settle(startSum: Int): Int {
        var sum = startSum
        if (httpVersion.isModified() || sum > 0) {
            sum = httpVersion.settleSingle(sum)
        }
        if (httpCode.isModified() || sum > 0) {
            sum = httpVersion.settleSingle(sum)
        }
        if (httpReason.isModified() || sum > 0) {
            sum = httpVersion.settleSingle(sum)
        }
        return super.settle(sum)
    }

    class Builder {
        var decodeResult: DecoderResult = DecoderResult.SUCCESS
            private set
        var version: VersionPointer? = null
            private set
        var code: IntPointer? = null
            private set
        var reason: StringPointer? = null
            private set
        var headers: HeadersPointer? = null
            private set
        var body: BodyPointer? = null
            private set

        fun setDecodeResult(result: DecoderResult) {
            this.decodeResult = result
        }

        fun setVersion(version: VersionPointer) {
            this.version = version
        }

        fun setCode(code: IntPointer) {
            this.code = code
        }

        fun setReason(reason: StringPointer) {
            this.reason = reason
        }

        fun setHeaders(headers: HeadersPointer) {
            this.headers = headers
        }

        fun setBody(body: BodyPointer) {
            this.body = body
        }

        private fun createError(reference: ByteBuf, decoderResult: DecoderResult) = DirtyHttpResponse(VersionPointer(0, HttpVersion.HTTP_1_1), IntPointer(0,0), StringPointer(0,""), BodyPointer(0, reference, 0), HeadersPointer(0, 0, reference, mutableMapOf()), reference, decoderResult)

        fun build(reference: ByteBuf): DirtyHttpResponse = if (decodeResult.isSuccess) {
            DirtyHttpResponse(
                checkNotNull(version) {"Version is required"},
                checkNotNull(code) {"Code is required"},
                checkNotNull(reason) {"Reason is required"},
                checkNotNull(body) {"Body is required"},
                checkNotNull(headers) {"Header is required"},
                reference
            )
        } else {
            createError(reference, decodeResult)
        }
    }

}

