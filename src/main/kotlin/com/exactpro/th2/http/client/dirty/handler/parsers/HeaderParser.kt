package com.exactpro.th2.http.client.dirty.handler.parsers

import com.exactpro.th2.http.client.dirty.handler.data.HttpHeaderPosition
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.HttpConstants
import io.netty.util.internal.AppendableCharSequence

class HeaderParser {
    fun parse(buffer: ByteBuf): MutableMap<String, HttpHeaderPosition> = mutableMapOf<String, HttpHeaderPosition>().apply {
        reset()
        while (true) {
            val startIndex = buffer.readerIndex()
            val name = parseLine(buffer)
            if (name.isEmpty()) break
            val endIndex = buffer.readerIndex()
            this[name] = HttpHeaderPosition(startIndex, endIndex)
        }
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
        if (!valueStarted) {
            return ""
        }
        if (i<0) byteBuf.readerIndex(byteBuf.writerIndex()) else byteBuf.readerIndex(i + 1)
        return name.toString()
    }

    private fun reset() {

    }

    companion object {
        const val DEFAULT_INITIAL_BUFFER_SIZE = 256
    }
}