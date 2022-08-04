package com.exactpro.th2.http.client.codec

import io.netty.buffer.Unpooled
import io.netty.handler.codec.DirtyRequestDecoder
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class RequestCodecTests {

    @Test
    fun `Request decode`() {
        val requestString = """
            POST /test/demo_form.php HTTP/1.1
            Host: w3schools.com

            name1=value1&name2=value2
            """.trimIndent()
        val codec = DirtyRequestDecoder()
        val decodeResult = codec.decodeSingle(Unpooled.buffer().writeBytes(requestString.toByteArray()))
        Assertions.assertNotNull(decodeResult)
        Assertions.assertEquals("POST", decodeResult!!.method.name())
        Assertions.assertEquals("/test/demo_form.php", decodeResult.url)
        Assertions.assertEquals("HTTP/1.1", decodeResult.version.text())
        Assertions.assertEquals("w3schools.com", decodeResult.headers["Host"])
        Assertions.assertEquals("\nname1=value1&name2=value2", decodeResult.body.toString(Charset.defaultCharset()))
    }

    @Test
    fun `Request decode without body`() {
        val requestString = """
            POST /test/demo_form.php HTTP/1.1
            Host: w3schools.com
            
            
            """.trimIndent()
        val codec = DirtyRequestDecoder()
        val decodeResult = codec.decodeSingle(Unpooled.buffer().writeBytes(requestString.toByteArray()))
        Assertions.assertNotNull(decodeResult)
        Assertions.assertEquals("POST", decodeResult!!.method.name())
        Assertions.assertEquals("/test/demo_form.php", decodeResult.url)
        Assertions.assertEquals("HTTP/1.1", decodeResult.version.text())
        Assertions.assertEquals("w3schools.com", decodeResult.headers["Host"])
        Assertions.assertEquals("\n", decodeResult.body.toString(Charset.defaultCharset()))
    }

}