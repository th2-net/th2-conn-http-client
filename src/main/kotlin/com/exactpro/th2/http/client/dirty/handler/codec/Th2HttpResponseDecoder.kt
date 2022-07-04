package com.exactpro.th2.http.client.dirty.handler.codec

import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.HttpResponseDecoder
import io.netty.handler.codec.http.LastHttpContent

class Th2HttpResponseDecoder: HttpResponseDecoder() {

    fun decode0(buffer: ByteBuf, out: MutableList<Any>): Int {
        val startReaderIndex = buffer.readerIndex()
        while (buffer.readableBytes() > 0) {
            decode(null, buffer, out)
            if (out.isNotEmpty()) {
                out.last().let {
                    when {
                        it is LastHttpContent -> return startReaderIndex
//                        requestMethod.name()[0] == 'H' && it is HttpResponse -> return startReaderIndex
                        else -> {}
                    }
                }
            }
        }
        return -1
    }

}