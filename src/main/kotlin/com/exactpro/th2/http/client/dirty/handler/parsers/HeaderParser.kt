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


package com.exactpro.th2.http.client.dirty.handler.parsers

import com.exactpro.th2.http.client.dirty.handler.data.pointers.HeadersPointer.HttpHeaderPosition
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.HttpConstants
import io.netty.util.internal.AppendableCharSequence

class HeaderParser {
    private var endOfHeaders = false
    private val result = mutableMapOf<String, HttpHeaderPosition>()

    fun getHeaders() = result.toMutableMap()

    fun parse(buffer: ByteBuf): Boolean {
        while (true) {
            val startIndex = buffer.readerIndex()
            buffer.markReaderIndex()
            val name = parseLine(buffer)
            if(name.isEmpty()||endOfHeaders) break
            val endIndex = buffer.readerIndex()
            result[name] = HttpHeaderPosition(startIndex, endIndex)
        }
        if (!endOfHeaders) buffer.resetReaderIndex()
        return endOfHeaders
    }

    private fun parseLine(byteBuf: ByteBuf): String {
        var valueStarted = false
        val name = AppendableCharSequence(DEFAULT_INITIAL_BUFFER_SIZE)
        val i = byteBuf.forEachByte {
            when {
                it == HttpConstants.LF -> {
                    false
                }
                valueStarted -> true
                it == HttpConstants.SP -> true
                it == HttpConstants.COLON -> {
                    valueStarted = true
                    true
                }
                else -> {
                    name.append((it.toInt() and 0xFF).toChar())
                    true
                }
            }
        }
        if (name.isNotEmpty() && name.charAtUnsafe(name.length - 1).code.toByte() == HttpConstants.CR) {
            name.setLength(name.length - 1)
        }
        if (!valueStarted) {
            if (name.isEmpty()) endOfHeaders = true
            return ""
        }
        if (i>0) byteBuf.readerIndex(i + 1)
        return name.toString()
    }

    fun reset() {
        result.clear()
        endOfHeaders = false
    }

    companion object {
        const val DEFAULT_INITIAL_BUFFER_SIZE = 256
    }
}