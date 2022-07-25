package com.exactpro.th2.http.client

import com.exactpro.th2.http.client.dirty.handler.parsers.HeaderParser
import com.exactpro.th2.http.client.dirty.handler.parsers.LineParser
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class ParserTests {

    @Test
    fun `Start line parser test`() {
        val firstLine = "GET /test HTTP1.1 ADDITIONAL WRONG DATA"
        val additional = "HEADER_NAME: HEADER_VALUE"
        val parser = LineParser()
        repeat(2) {
            parser.test(firstLine, additional)
        }
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
            parser.test(mainPart, body)
        }
    }

    private fun HeaderParser.test(headers: Map<String, String>, body: String) {
        val separator = "\r\n"
        val headersString = headers.toList().joinToString(separator) {"${it.first}: ${it.second}"}
        val data = "$headersString$separator$separator$body"
        val buffer = Unpooled.buffer().writeBytes(data.toByteArray())
        val headersPositions = parse(buffer)
        headers.forEach {
            Assertions.assertTrue(headersPositions.contains(it.key))
            Assertions.assertTrue(headersPositions[it.key]!!.let { position -> position.end-position.start == "${it.key}: ${it.value}".length + separator.length })
        }
    }

    private fun LineParser.test(statusLine: String, additional: String) {
        val separator = "\r\n"
        val buffer = Unpooled.buffer().writeBytes("$statusLine$separator$additional".toByteArray())
        val parts = parse(buffer)
        statusLine.split(" ").let {
            Assertions.assertEquals(it.size, parts.size)
            it.forEachIndexed { index, value ->
                Assertions.assertEquals(value, parts[index].first) { "$index element: $value" }
                Assertions.assertEquals(statusLine.indexOf(value), parts[index].second)
            }
        }

        Assertions.assertEquals(additional, buffer.toString(Charset.defaultCharset()))
    }
}