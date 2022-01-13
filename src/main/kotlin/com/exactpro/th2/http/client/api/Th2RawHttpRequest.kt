package com.exactpro.th2.http.client.api

import rawhttp.core.EagerHttpRequest
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RequestLine
import rawhttp.core.body.BodyReader
import rawhttp.core.body.HttpMessageBody
import java.net.InetAddress

class Th2RawHttpRequest(
    requestLine: RequestLine,
    headers: RawHttpHeaders,
    bodyReader: BodyReader?,
    senderAddress: InetAddress?,
    val parentEventId: String,
    val metadataProperties: Map<String, String>
) : RawHttpRequest(requestLine, headers, bodyReader, senderAddress) {

    override fun withBody(body: HttpMessageBody?, adjustHeaders: Boolean): Th2RawHttpRequest = withBody(body, adjustHeaders) { headers: RawHttpHeaders, bodyReader: BodyReader? ->
        Th2RawHttpRequest(
            startLine,
            headers,
            bodyReader,
            senderAddress.orElse(null),
            parentEventId,
            metadataProperties
        )
    }

    override fun withRequestLine(requestLine: RequestLine): Th2RawHttpRequest {
        val newHost = RawHttpHeaders.hostHeaderValueFor(requestLine.uri) ?: error("RequestLine host must not be null")
        val headers: RawHttpHeaders = when {
            newHost.equals(headers.getFirst("Host").orElse(""), true) -> headers
            else -> RawHttpHeaders.newBuilderSkippingValidation(headers)
                .overwrite("Host", newHost)
                .build()
        }

        return Th2RawHttpRequest(
            requestLine,
            headers,
            body.orElse(null),
            senderAddress.orElse(null),
            parentEventId,
            metadataProperties
        )
    }

    override fun withHeaders(headers: RawHttpHeaders): Th2RawHttpRequest = withHeaders(headers, true)

    override fun withHeaders(headers: RawHttpHeaders, append: Boolean): Th2RawHttpRequest = Th2RawHttpRequest(
        startLine,
        if (append) getHeaders().and(headers) else headers.and(getHeaders()),
        body.orElse(null),
        senderAddress.orElse(null),
        parentEventId,
        metadataProperties
    )

//    override fun eagerly(): EagerHttpRequest {
//        throw UnsupportedOperationException("Unsupported eagerly call of request. It's client side, no need to eager request")
//    }
}




