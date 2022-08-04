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

import com.exactpro.th2.http.client.dirty.handler.data.pointers.HeadersPointer.HttpHeaderDetails
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.HttpConstants
import io.netty.util.internal.AppendableCharSequence

class HeaderParser: LineParser {
    private var valueStarted = false
    private var startOfHeadersIndex: Int? = null
    private val nameBuilder = AppendableCharSequence(DEFAULT_INITIAL_BUFFER_SIZE)
    private val valueBuilder = AppendableCharSequence(DEFAULT_INITIAL_BUFFER_SIZE)
    private val result = mutableMapOf<String, HttpHeaderDetails>()

    fun getHeaders() = result.toMutableMap()

    fun parseHeaders(buffer: ByteBuf): Boolean {
        if (startOfHeadersIndex == null) startOfHeadersIndex = buffer.readerIndex()
        while (true) {
            valueStarted = false
            val startIndex = buffer.readerIndex()
            if (!parseLine(buffer)) {
                resetBuilders()
                break
            }

            nameBuilder.removeCR()
            if(nameBuilder.isEmpty()) {
                buffer.resetReaderIndex()
                return true
            }
            valueBuilder.removeCR()

            result[nameBuilder.toString()] = HttpHeaderDetails(startIndex, buffer.readerIndex(), valueBuilder.toString())
            resetBuilders()
        }
        return false
    }

    override fun parse(byte: Byte, index: Int) {
        when {
            byte == HttpConstants.COLON -> {
                valueStarted = true
            }
            byte == HttpConstants.SP && valueBuilder.isEmpty() -> Unit
            valueStarted -> valueBuilder.append((byte.toInt() and 0xFF).toChar())
            else -> nameBuilder.append((byte.toInt() and 0xFF).toChar())
        }
    }

    fun reset() {
        result.clear()
        startOfHeadersIndex = null
        resetBuilders()
    }

    private fun resetBuilders() {
        nameBuilder.reset()
        valueBuilder.reset()
    }

    private fun AppendableCharSequence.removeCR() {
        if (this.isNotEmpty() && this.charAtUnsafe(this.length - 1).code.toByte() == HttpConstants.CR) this.setLength(this.length - 1)
    }

    companion object {
        const val DEFAULT_INITIAL_BUFFER_SIZE = 256
    }
}