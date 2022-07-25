package com.exactpro.th2.http.client

import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpHeaders
import com.exactpro.th2.http.client.dirty.handler.parsers.HeaderParser
import com.exactpro.th2.http.client.dirty.handler.parsers.LineParser
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class RequestDataTests {

    @Test
    fun `Header tests`() {
        val requestString = """
            POST /test/demo_form.php HTTP/1.1${"\r"}
            Accept: Something${"\r"}
            Connection: close${"\r"}
            Host: w3schools.com${"\r"}
            ${"\r"}
            name1=value1&name2=value2
        """.trimIndent()
        val buffer = Unpooled.buffer().writeBytes(requestString.toByteArray())

        val headerParser = HeaderParser()
        val lineParser = LineParser()
        lineParser.parse(buffer)
        val startOfHeader = buffer.readerIndex()
        val headers = headerParser.parse(buffer)
        val container = DirtyHttpHeaders(startOfHeader, buffer.readerIndex()-startOfHeader, Unpooled.buffer()
            .writeBytes(requestString.toByteArray()), headers)

        Assertions.assertEquals("Something", container["Accept"])
        Assertions.assertEquals("close", container["Connection"])
        Assertions.assertEquals("w3schools.com", container["Host"])

        container.remove("Accept")

        Assertions.assertEquals("""
            POST /test/demo_form.php HTTP/1.1
            Connection: close
            Host: w3schools.com
            
            name1=value1&name2=value2
        """.trimIndent(), container.value.readerIndex(0).toString(Charset.defaultCharset()))

        Assertions.assertEquals(null, container["Accept"])
        Assertions.assertEquals("close", container["Connection"])
        Assertions.assertEquals("w3schools.com", container["Host"])

        container["Accept"] = "Something"

        Assertions.assertEquals("Something", container["Accept"])
        Assertions.assertEquals("close", container["Connection"])
        Assertions.assertEquals("w3schools.com", container["Host"])

        Assertions.assertEquals("""
            POST /test/demo_form.php HTTP/1.1
            Connection: close
            Host: w3schools.com
            Accept: Something
            
            name1=value1&name2=value2
        """.trimIndent(), container.value.readerIndex(0).toString(Charset.defaultCharset()).replace("\r", ""))

        container["Test"] = "value"

        Assertions.assertEquals("Something", container["Accept"])
        Assertions.assertEquals("value", container["Test"])
        Assertions.assertEquals("close", container["Connection"])
        Assertions.assertEquals("w3schools.com", container["Host"])

        Assertions.assertEquals("""
            POST /test/demo_form.php HTTP/1.1
            Connection: close
            Host: w3schools.com
            Accept: Something
            Test: value
            
            name1=value1&name2=value2
        """.trimIndent(), container.value.readerIndex(0).toString(Charset.defaultCharset()).replace("\r", ""))
    }
}