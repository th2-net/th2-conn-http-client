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

import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.HttpConstants
import io.netty.util.internal.AppendableCharSequence

class StartLineParser: LineParser {
    private val builder = AppendableCharSequence(DEFAULT_INITIAL_BUFFER_SIZE)
    private val parts = mutableListOf<Pair<String, Int>>()
    private var startOfElement = 0

    val lineParts
        get() = parts.toList()

    override fun parseLine(buffer: ByteBuf): Boolean {
        startOfElement = buffer.readerIndex()
        return super.parseLine(buffer).also {
            if (it) {
                if (builder.isNotEmpty() && builder.charAtUnsafe(builder.length - 1).code.toByte() == HttpConstants.CR) builder.setLength(builder.length - 1)
                settlePart()
            } else {
                reset()
            }
        }
    }

    override fun parse(byte: Byte, index: Int) {
        when(byte) {
            HttpConstants.SP -> {
                settlePart()
                startOfElement = index+1
            }
            else -> {
                builder.append((byte.toInt() and 0xFF).toChar())
            }
        }
    }

    private fun settlePart() {
        if (builder.isNotEmpty()) {
            parts.add(builder.toString() to startOfElement)
            builder.reset()
        }
    }

    fun reset() {
        builder.reset()
        parts.clear()
    }

    companion object {
        const val DEFAULT_INITIAL_BUFFER_SIZE = 256
    }



}