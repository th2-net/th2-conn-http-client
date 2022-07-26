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

class DirtyHttpResponse(private val httpVersion: VersionPointer, private val httpCode: IntPointer, private val httpReason: StringPointer, httpBody: BodyPointer, headers: HeadersPointer, reference: ByteBuf): DirtyHttpMessage(headers, httpBody, reference) {

    var version: NettyHttpVersion
        get() = httpVersion.value
        set(value) = this.httpVersion.let {
            reference.replace(it.position, reference.writerIndex(), value.text())
            it.value = value
            settle()
        }

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
}