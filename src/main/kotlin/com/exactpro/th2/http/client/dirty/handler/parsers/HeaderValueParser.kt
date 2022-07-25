package com.exactpro.th2.http.client.dirty.handler.parsers

import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.HttpConstants
import io.netty.util.ByteProcessor
import io.netty.util.internal.AppendableCharSequence

class HeaderValueParser: ByteProcessor {

    private val headerValue = AppendableCharSequence(DEFAULT_INITIAL_BUFFER_SIZE)

    private var valueStarted = false

    fun parse(byteBuf: ByteBuf): String {
        reset()
        byteBuf.forEachByte(this)
        if (headerValue.last() == '\r') headerValue.setLength(headerValue.length-1)
        return headerValue.toString()
    }

    override fun process(byte: Byte): Boolean {
        return when {
            byte == HttpConstants.LF -> false
            byte == HttpConstants.SP -> true
            byte == HttpConstants.COLON && !valueStarted -> {
                valueStarted = true
                true
            }
            !valueStarted -> true
            else -> {
                headerValue.append((byte.toInt() and 0xFF).toChar())
                true
            }
        }
    }

    private fun reset() {
        headerValue.reset()
        valueStarted = false
    }

    companion object {
        const val DEFAULT_INITIAL_BUFFER_SIZE = 256
    }
}