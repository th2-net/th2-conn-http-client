package io.netty.handler.codec

import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpResponse
import com.exactpro.th2.http.client.dirty.handler.data.pointers.BodyPointer
import com.exactpro.th2.http.client.dirty.handler.data.pointers.HeadersPointer
import com.exactpro.th2.http.client.dirty.handler.data.pointers.IntPointer
import com.exactpro.th2.http.client.dirty.handler.data.pointers.StringPointer
import com.exactpro.th2.http.client.dirty.handler.data.pointers.VersionPointer
import com.exactpro.th2.http.client.dirty.handler.parsers.HeaderParser
import com.exactpro.th2.http.client.dirty.handler.parsers.StartLineParser
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpVersion

class DirtyResponseDecoder: ByteToMessageDecoder() {
    private var currentState = State.READ_INITIAL
    private var currentMessageBuilder = DirtyHttpResponse.Builder()
    private val lineParser = StartLineParser()
    private val headerParser = HeaderParser()

    private var firedChannelRead = false

    init {
        cumulation = Unpooled.buffer()
        isSingleDecode = true
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is ByteBuf) {
            val out = CodecOutputList.newInstance()
            try {
                callDecode(ctx, msg.retain(), out)
            } catch (e: DecoderException) {
                throw e
            } catch (e: java.lang.Exception) {
                throw DecoderException(e)
            } finally {
                try {
                    val size = out.size
                    firedChannelRead = firedChannelRead || out.insertSinceRecycled()
                    fireChannelRead(ctx, out, size)
                } finally {
                    out.recycle()
                }
            }
        } else {
            ctx.fireChannelRead(msg)
        }
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        discardSomeReadBytes()
        if (!firedChannelRead && !ctx.channel().config().isAutoRead) {
            ctx.read()
        }
        firedChannelRead = false
        ctx.fireChannelReadComplete()
    }

    override fun decode(ctx: ChannelHandlerContext, `in`: ByteBuf, out: MutableList<Any>) {
        val beforeDecode = `in`.readerIndex()
        val result = decodeSingle(`in`)
        cumulation.writeBytes(`in`, beforeDecode, `in`.readerIndex() - beforeDecode)
        if (result) {
            out.add(currentMessageBuilder.build(cumulation.retainedDuplicate()))
            currentMessageBuilder = DirtyHttpResponse.Builder()
            cumulation.clear()
        }
    }

    /**
     * @return true if decode was completed
     */
    private fun decodeSingle(buffer: ByteBuf): Boolean {
        if (!buffer.isReadable) false
        try {
            when(currentState) {
                State.SKIP_CONTROL_CHARS -> {
                    currentState = State.READ_INITIAL
                    return decodeSingle(buffer)
                }
                State.READ_INITIAL -> {
                    if (!lineParser.parseLine(buffer)) return false
                    val parts = lineParser.lineParts
                    lineParser.reset()
                    if (parts.size < 3) {
                        throw IllegalStateException("Start line is less than 3 parts")
                    }
                    currentMessageBuilder.setVersion(parts[0].let { VersionPointer(it.second, HttpVersion.valueOf(it.first)) })
                    currentMessageBuilder.setCode(parts[1].let { IntPointer(it.second, it.first.toInt()) })
                    currentMessageBuilder.setReason( parts[2].let { StringPointer(it.second, it.first) })
                    currentState = State.READ_HEADER
                    return decodeSingle(buffer)
                }
                State.READ_HEADER -> {
                    val startOfHeaders = buffer.readerIndex()
                    if (!headerParser.parseHeaders(buffer)) return false
                    val headers = headerParser.getHeaders()
                    headerParser.reset()
                    val endOfHeaders = buffer.readerIndex()
                    currentMessageBuilder.setHeaders(HeadersPointer(startOfHeaders, endOfHeaders-startOfHeaders, buffer, headers))
                    currentState = State.READ_FIXED_LENGTH_CONTENT
                    return decodeSingle(buffer)
                }
                State.READ_FIXED_LENGTH_CONTENT -> {
                    val headers = checkNotNull(currentMessageBuilder.headers)
                    headers["Content-Length"]?.toInt()?.let { contentLength ->
                        currentMessageBuilder.setBody(BodyPointer(buffer.readerIndex(), buffer, contentLength))
                        if (buffer.writerIndex() < buffer.readerIndex() + contentLength) return false
                        buffer.readerIndex(buffer.readerIndex() + contentLength)
                    } ?: currentMessageBuilder.setBody(BodyPointer.Empty(buffer.readerIndex(), buffer))

                    currentState = State.SKIP_CONTROL_CHARS
                    return true
                }
                else -> {
                    throw java.lang.IllegalStateException("Wrong state of decode")
                }
            }
        } catch (e: Exception) {
            currentMessageBuilder.setDecodeResult(DecoderResult.failure(e))
            lineParser.reset()
            headerParser.reset()
            reset()
            buffer.readerIndex(buffer.writerIndex())
            return true
        }
    }

    private fun reset() {
        currentState = State.SKIP_CONTROL_CHARS
    }

    private enum class State {
        SKIP_CONTROL_CHARS, READ_INITIAL, READ_HEADER, READ_VARIABLE_LENGTH_CONTENT, READ_FIXED_LENGTH_CONTENT, READ_CHUNK_SIZE, READ_CHUNKED_CONTENT, READ_CHUNK_DELIMITER, READ_CHUNK_FOOTER, BAD_MESSAGE
    }
}