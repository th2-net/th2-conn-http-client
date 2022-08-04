package com.exactpro.th2.http.client

import com.exactpro.th2.http.client.dirty.handler.parsers.HeaderParser
import com.exactpro.th2.http.client.dirty.handler.parsers.LineParser
import com.exactpro.th2.http.client.dirty.handler.parsers.StartLineParser
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class ParserTests {

    @Test
    fun `line parser`() {
        val buffer = Unpooled.buffer().writeBytes("___\n___\n___".toByteArray())

        LineParser { _, _ ->

        }.let { parser ->
            Assertions.assertEquals(0, buffer.readerIndex())
            Assertions.assertTrue(parser.parseLine(buffer))
            Assertions.assertEquals(4, buffer.readerIndex())
            Assertions.assertTrue(parser.parseLine(buffer))
            Assertions.assertEquals(8, buffer.readerIndex())
            Assertions.assertFalse(parser.parseLine(buffer))
            Assertions.assertEquals(8, buffer.readerIndex())
        }
    }

    @Test
    fun `Start line parser test`() {
        val firstLine = "GET /test HTTP1.1 ADDITIONAL WRONG DATA"
        val additional = "HEADER_NAME: HEADER_VALUE"
        val parser = StartLineParser()
        repeat(2) {
            parser.test(firstLine, additional)
        }
    }

    @Test
    fun `Response status parser test`() {
        val firstLine = "HTTP1.1 200 OK WRONG DATA"
        val additional = "HEADER_NAME: HEADER_VALUE"
        val parser = StartLineParser()
        repeat(2) {
            parser.test(firstLine, additional)
        }

        val buffer = Unpooled.buffer().writeBytes(firstLine.toByteArray())
        Assertions.assertFalse(parser.parseLine(buffer))
        Assertions.assertEquals(0, buffer.readerIndex())
    }

    @Test
    fun `Header parser test`() {
        val mainPart = mapOf(
            "HEADER_NAME0" to "HEADER_VALUE0",
            "HEADER_NAME1" to "HEADER_VALUE1",
            "HEADER_NAME2" to "HEADER_VALUE2"
        )
        val body = "{some data}"
        val parser = HeaderParser()
        repeat(2) {
            Assertions.assertDoesNotThrow({
                parser.test(mainPart, body)
            }, "Exception on attempt: $it")
        }

        var stringHeaders = """
            HEADER_NAME0: HEADER_VALUE0
            HEADER_NAME1: HEADER_VALUE1
            HEADER_NAME2
        """.trimIndent()
        var buffer = Unpooled.buffer().writeBytes(stringHeaders.toByteArray())

        Assertions.assertFalse(parser.parseHeaders(buffer))
        buffer.release()
        stringHeaders = """
            HEADER_NAME2: HEADER_VALUE2
            
            
        """.trimIndent()
        buffer = Unpooled.buffer().writeBytes(stringHeaders.toByteArray())
        Assertions.assertTrue(parser.parseHeaders(buffer))

        parser.getHeaders().let {
            Assertions.assertEquals(3, it.size)
            Assertions.assertTrue(it.contains("HEADER_NAME0"))
            Assertions.assertTrue(it.contains("HEADER_NAME1"))
            Assertions.assertTrue(it.contains("HEADER_NAME2"))
        }
    }

    private fun HeaderParser.test(headers: Map<String, String>, body: String) {
        val separator = "\r\n"
        val headersString = headers.toList().joinToString(separator) {"${it.first}: ${it.second}"}
        val data = "$headersString$separator$separator$body"
        val buffer = Unpooled.buffer().writeBytes(data.toByteArray())
        check(parseHeaders(buffer)) {"Headers must have empty line"}

        val headersPositions = getHeaders()
        this.reset()

        headers.forEach {
            Assertions.assertTrue(headersPositions.contains(it.key))
            Assertions.assertTrue(headersPositions[it.key]!!.let { position -> position.end-position.start == "${it.key}: ${it.value}".length + separator.length })
        }
    }

    private fun StartLineParser.test(statusLine: String, additional: String) {
        val separator = "\r\n"
        val buffer = Unpooled.buffer().writeBytes("$statusLine$separator$additional".toByteArray())
        check(parseLine(buffer)) {"Must found end of the line"}
        val result = lineParts
        reset()
        statusLine.split(" ").let {
            Assertions.assertEquals(it.size, result.size)
            it.forEachIndexed { index, value ->
                Assertions.assertEquals(value, result[index].first) { "$index element: $value" }
                Assertions.assertEquals(statusLine.indexOf(value), result[index].second)
            }
        }

        Assertions.assertEquals(additional, buffer.toString(Charset.defaultCharset()))
    }
}