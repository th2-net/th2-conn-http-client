/*
 * Copyright 2021-2023 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exactpro.th2.http.client.api.decorators

import com.exactpro.th2.common.event.EventUtils.generateUUID
import com.exactpro.th2.common.grpc.EventID
import rawhttp.core.EagerHttpRequest
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RequestLine
import rawhttp.core.body.BodyReader
import rawhttp.core.body.HttpMessageBody
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong

class Th2RawHttpRequest(
    requestLine: RequestLine,
    headers: RawHttpHeaders,
    bodyReader: BodyReader?,
    senderAddress: InetAddress?,
    val parentEventId: EventID?,
    val metadataProperties: Map<String, String>,
    val th2RequestId: String = nextRequestId(),
) : RawHttpRequest(requestLine, headers, bodyReader, senderAddress) {



    override fun withBody(body: HttpMessageBody?, adjustHeaders: Boolean): Th2RawHttpRequest =
        withBody(body, adjustHeaders) { headers: RawHttpHeaders, bodyReader: BodyReader? ->
            Th2RawHttpRequest(
                startLine,
                headers,
                bodyReader,
                senderAddress.orElse(null),
                parentEventId,
                metadataProperties,
                th2RequestId,
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
            metadataProperties,
            th2RequestId,
        )
    }

    override fun withHeaders(headers: RawHttpHeaders): Th2RawHttpRequest = withHeaders(headers, true)

    override fun withHeaders(headers: RawHttpHeaders, append: Boolean): Th2RawHttpRequest = Th2RawHttpRequest(
        startLine,
        if (append) getHeaders().and(headers) else headers.and(getHeaders()),
        body.orElse(null),
        senderAddress.orElse(null),
        parentEventId,
        metadataProperties,
        th2RequestId,
    )

    override fun eagerly(): EagerHttpRequest {
        throw UnsupportedOperationException("Unsupported eagerly call of request. It's client side, no need to eager request")
    }

    companion object {
        private val BASE_REQUEST_ID = generateUUID()
        private val REQUEST_ID_COUNTER = Instant.now().run {
            AtomicLong(epochSecond * SECONDS.toNanos(1) + nano)
        }

        private fun nextRequestId() = "$BASE_REQUEST_ID-${REQUEST_ID_COUNTER.incrementAndGet()}"
    }
}




