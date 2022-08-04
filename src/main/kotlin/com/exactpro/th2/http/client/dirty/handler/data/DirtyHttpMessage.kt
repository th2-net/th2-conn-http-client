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
import com.exactpro.th2.http.client.dirty.handler.data.pointers.Pointer
import com.exactpro.th2.http.client.dirty.handler.data.pointers.VersionPointer
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.DecoderResult

typealias NettyHttpMethod = io.netty.handler.codec.http.HttpMethod
typealias NettyHttpVersion = io.netty.handler.codec.http.HttpVersion

abstract class DirtyHttpMessage(protected val httpVersion: VersionPointer, val headers: HeadersPointer, private val httpBody: BodyPointer, val reference: ByteBuf, val decoderResult: DecoderResult = DecoderResult.SUCCESS) {

    var body: ByteBuf
        get() = httpBody.reference.readerIndex(httpBody.position)
        set(value) {
            this.httpBody.reference.writerIndex(this.httpBody.position).writeBytes(value)
        }

    var version: NettyHttpVersion
        get() = httpVersion.value
        set(value) = this.httpVersion.let {
            reference.replace(it.position, reference.writerIndex(), value.text())
            it.value = value
            settle()
        }

    protected open fun settle(startSum: Int = 0): Int {
        var sum = startSum
        if (headers.isModified() || sum > 0) {
            sum = headers.settleSingle(sum)
        }
        if (httpBody.isModified() || sum > 0) {
            sum = httpBody.settleSingle(sum)
        }
        return sum
    }

    companion object {
        fun Pointer.settleSingle(amount: Int): Int {
            move(amount)
            return (expansion + amount).also {
                settle()
            }
        }
    }
}