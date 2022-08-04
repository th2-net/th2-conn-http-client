package com.exactpro.th2.http.client.dirty.handler.parsers

import com.exactpro.th2.http.client.dirty.handler.forEachByteIndexed
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.HttpConstants

fun interface LineParser {

    fun parse(byte: Byte, index: Int)

    fun parseLine(buffer: ByteBuf): Boolean {
        var endOfLine = false
        buffer.markReaderIndex()
        val index = buffer.forEachByteIndexed { index, byte ->
            when (byte) {
                HttpConstants.LF -> {
                    endOfLine = true
                    false
                }
                else -> {
                    parse(byte, index)
                    true
                }
            }
        }
        if (index>0) {
            if (!endOfLine) {
                buffer.resetReaderIndex()
            } else {
                buffer.readerIndex(index+1)
            }
        } else {
            if (endOfLine) {
                buffer.readerIndex(buffer.writerIndex())
            }
        }
        return endOfLine
    }
}