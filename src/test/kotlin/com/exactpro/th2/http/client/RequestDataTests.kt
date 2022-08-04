package com.exactpro.th2.http.client

import com.exactpro.th2.http.client.dirty.handler.data.pointers.HeadersPointer
import com.exactpro.th2.http.client.dirty.handler.parsers.HeaderParser
import com.exactpro.th2.http.client.dirty.handler.parsers.StartLineParser
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
        val lineParser = StartLineParser()
        lineParser.parseLine(buffer)
        lineParser.reset()
        val startOfHeader = buffer.readerIndex()
        check(headerParser.parseHeaders(buffer))
        val headers = headerParser.getHeaders()
        headerParser.reset()
        val resultHeaders = HeadersPointer(startOfHeader, buffer.readerIndex()-startOfHeader, buffer, headers)

        Assertions.assertEquals("Something", resultHeaders["Accept"])
        Assertions.assertEquals("close", resultHeaders["Connection"])
        Assertions.assertEquals("w3schools.com", resultHeaders["Host"])

        resultHeaders.remove("Accept")

        Assertions.assertEquals("""
            POST /test/demo_form.php HTTP/1.1
            Connection: close
            Host: w3schools.com
            
            name1=value1&name2=value2
        """.trimIndent(), buffer.readerIndex(0).toString(Charset.defaultCharset()))

        Assertions.assertEquals(null, resultHeaders["Accept"])
        Assertions.assertEquals("close", resultHeaders["Connection"])
        Assertions.assertEquals("w3schools.com", resultHeaders["Host"])

        resultHeaders["Accept"] = "Something"

        Assertions.assertEquals("Something", resultHeaders["Accept"])
        Assertions.assertEquals("close", resultHeaders["Connection"])
        Assertions.assertEquals("w3schools.com", resultHeaders["Host"])

        Assertions.assertEquals("""
            POST /test/demo_form.php HTTP/1.1
            Connection: close
            Host: w3schools.com
            Accept: Something
            
            name1=value1&name2=value2
        """.trimIndent(), buffer.readerIndex(0).toString(Charset.defaultCharset()).replace("\r", ""))

        resultHeaders["Test"] = "value"

        Assertions.assertEquals("Something", resultHeaders["Accept"])
        Assertions.assertEquals("value", resultHeaders["Test"])
        Assertions.assertEquals("close", resultHeaders["Connection"])
        Assertions.assertEquals("w3schools.com", resultHeaders["Host"])

        Assertions.assertEquals("""
            POST /test/demo_form.php HTTP/1.1
            Connection: close
            Host: w3schools.com
            Accept: Something
            Test: value
            
            name1=value1&name2=value2
        """.trimIndent(), buffer.readerIndex(0).toString(Charset.defaultCharset()).replace("\r", ""))
    }
}