package com.exactpro.th2.http.client.dirty.handler.parsers

import com.exactpro.th2.http.client.dirty.handler.forEachByteIndexed
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.HttpConstants
import io.netty.util.internal.AppendableCharSequence

class LineParser {
    private val builder = AppendableCharSequence(DEFAULT_INITIAL_BUFFER_SIZE)
    private val parts = mutableListOf<Pair<String, Int>>()
    private var startOfElement = 0

    fun parse(buffer: ByteBuf): List<Pair<String, Int>> {
        reset()
        val index = buffer.forEachByteIndexed(this::process)
        if (index > 0 && buffer.isReadable) buffer.readerIndex(index+1)
        return parts.toList()
    }

    private fun reset() {
        builder.reset()
        parts.clear()
        startOfElement = 0
    }

    private fun settlePart(index: Int) {
        if (builder.isNotEmpty()) {
            parts.add(builder.toString() to startOfElement)
            builder.reset()
        }
        startOfElement = index+1
    }

    private fun process(index: Int, value: Byte): Boolean = when (value) {
        HttpConstants.SP -> {
            settlePart(index)
            true
        }
        HttpConstants.LF -> {
            builder.length.let { len ->
                // Drop CR if we had a CRLF pair
                if (builder.isNotEmpty() && builder.charAtUnsafe(len - 1).code.toByte() == HttpConstants.CR) {
                    builder.setLength(len - 1)
                }
            }
            settlePart(index)
            false
        }
        else -> {
            builder.append((value.toInt() and 0xFF).toChar())
            true
        }
    }

    companion object {
        const val DEFAULT_INITIAL_BUFFER_SIZE = 256
    }

}