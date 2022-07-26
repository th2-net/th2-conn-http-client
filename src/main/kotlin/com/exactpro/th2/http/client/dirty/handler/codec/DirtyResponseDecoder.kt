package com.exactpro.th2.http.client.dirty.handler.codec

import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpResponse
import com.exactpro.th2.http.client.dirty.handler.parsers.HeaderParser
import com.exactpro.th2.http.client.dirty.handler.parsers.LineParser
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.http.HttpObjectDecoder

class DirtyResponseDecoder: ByteToMessageDecoder() {
    private var currentState = State.READ_INITIAL
    private var currentMessageBuilder = DirtyHttpResponse.Builder()
    private val lineParser = LineParser()
    private val headerParser = HeaderParser()

    override fun decode(ctx: ChannelHandlerContext, `in`: ByteBuf, out: MutableList<Any>) {
        TODO("Not yet implemented")
    }

    fun decodePart(ctx: ChannelHandlerContext, `in`: ByteBuf, out: MutableList<Any>) {
        when(currentState) {
            State.READ_INITIAL -> {
                if (!lineParser.parse(`in`)) {
                    return
                }
                val initialLine = lineParser.lineParts
                lineParser.reset()

//                if (initialLine.size < 3) { //TODO: Return decode result failed
//                    currentState = State.SKIP_CONTROL_CHARS
//                    return
//                }
            }
        }
    }

    private enum class State {
        READ_INITIAL, READ_HEADER, READ_VARIABLE_LENGTH_CONTENT, READ_FIXED_LENGTH_CONTENT, READ_CHUNK_SIZE, READ_CHUNKED_CONTENT, READ_CHUNK_DELIMITER, READ_CHUNK_FOOTER, BAD_MESSAGE, UPGRADED
    }
}