package com.exactpro.th2.http.client.util

import rawhttp.core.EagerHttpResponse
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RequestLine
import rawhttp.core.StartLine
import rawhttp.core.StatusLine
import rawhttp.core.body.BodyReader
import rawhttp.core.body.EagerBodyReader
import rawhttp.core.errors.InvalidHttpResponse
import java.io.IOException
import java.io.InputStream

fun BodyReader.tryClose() = runCatching(this::close)

@Throws(IOException::class)
fun RawHttp.parseResponseEagerly(inputStream: InputStream, requestLine: RequestLine): EagerHttpResponse<Void> {
    val statusLine: StatusLine = metadataParser.parseStatusLine(inputStream)
    val headers: RawHttpHeaders = metadataParser.parseHeaders(inputStream) { message: String?, lineNumber: Int ->
        InvalidHttpResponse(message, lineNumber + 1)
    }
    val bodyReader = if (RawHttp.responseHasBody(statusLine, requestLine)) createEagerBodyReader(inputStream, statusLine, headers) else null
    return EagerHttpResponse(null, null, statusLine, headers, bodyReader)
}

private fun RawHttp.createEagerBodyReader(inputStream: InputStream, startLine: StartLine, headers: RawHttpHeaders): EagerBodyReader {
    return EagerBodyReader(getFramedBody(startLine, headers), inputStream)
}