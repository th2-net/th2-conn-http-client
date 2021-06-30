package com.exactpro.th2.http.client.api


import java.net.InetAddress
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RequestLine
import rawhttp.core.body.BodyReader
import rawhttp.core.body.HttpMessageBody

class Th2RawHttpRequest (requestLine: RequestLine, headers: RawHttpHeaders, bodyReader: BodyReader?, senderAddress: InetAddress?, val parentEventId: String)
    : RawHttpRequest(requestLine, headers, bodyReader, senderAddress) {

    override fun withBody(body: HttpMessageBody?): RawHttpRequest? {
        return withBody(body, true)
    }

    override fun withBody(body: HttpMessageBody?, adjustHeaders: Boolean): RawHttpRequest? {
        return withBody(body, adjustHeaders) { headers: RawHttpHeaders, bodyReader: BodyReader? ->
            Th2RawHttpRequest(
                startLine,
                headers,
                bodyReader,
                senderAddress.orElse(null),
                this.parentEventId
            )
        }
    }

    override fun withRequestLine(requestLine: RequestLine): RawHttpRequest {
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
            this.parentEventId
        )
    }

    override fun withHeaders(headers: RawHttpHeaders): RawHttpRequest {
        return withHeaders(headers, true)
    }

    override fun withHeaders(headers: RawHttpHeaders, append: Boolean): RawHttpRequest {
        return Th2RawHttpRequest(
            startLine,
            if (append) getHeaders().and(headers) else headers.and(getHeaders()),
            body.orElse(null),
            senderAddress.orElse(null),
            this.parentEventId
        )
    }
}
