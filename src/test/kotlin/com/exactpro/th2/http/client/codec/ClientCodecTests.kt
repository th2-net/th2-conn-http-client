package com.exactpro.th2.http.client.codec

import com.exactpro.th2.http.client.dirty.handler.codec.DirtyHttpClientCodec
import com.exactpro.th2.http.client.dirty.handler.data.NettyHttpVersion
import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderResult
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class ClientCodecTests {

    @Test
    fun `fully response decode`() {
        val responseString = """
            HTTP/1.1 200 ok
            Host: w3schools.com

            name1=value1&name2=value2
            """.trimIndent()
        val codec = DirtyHttpClientCodec()
        val buffer = Unpooled.buffer().writeBytes(responseString.toByteArray())
        val decodeResult = codec.onResponse(buffer)
        Assertions.assertNotNull(decodeResult) {"Response \n$responseString\n must produce single object as result of decode"}
        Assertions.assertEquals(DecoderResult.SUCCESS, decodeResult!!.decoderResult)
        Assertions.assertEquals(NettyHttpVersion.HTTP_1_1, decodeResult.version)
        Assertions.assertEquals(200, decodeResult.code)
        Assertions.assertEquals("ok", decodeResult.reason)
    }

    @Test
    fun `partly response decode`() {
        val responseString = """
            HTTP/1.1 200 ok
            Host: w3schools.com

            name1=value1&name2=value2
            """.trimIndent()
        val codec = DirtyHttpClientCodec()
        val firstPart = Unpooled.buffer().writeBytes(responseString.subSequence(0, responseString.length/2).toString().toByteArray())
        val secondPart = Unpooled.buffer().writeBytes(responseString.subSequence(responseString.length/2, responseString.length).toString().toByteArray())
        Assertions.assertNull(codec.onResponse(firstPart))
        val decodeResult = codec.onResponse(secondPart)
        Assertions.assertNotNull(decodeResult) {"Response \n$responseString\n must produce single object as result of decode"}
        Assertions.assertEquals(DecoderResult.SUCCESS, decodeResult!!.decoderResult)
        Assertions.assertEquals(NettyHttpVersion.HTTP_1_1, decodeResult.version)
        Assertions.assertEquals(200, decodeResult.code)
        Assertions.assertEquals("ok", decodeResult.reason)
    }

}