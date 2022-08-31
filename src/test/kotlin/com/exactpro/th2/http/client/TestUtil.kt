/*
 * Copyright 2021-2021 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.http.client

import com.exactpro.th2.http.client.util.Certificate
import com.exactpro.th2.http.client.util.HOST_HEADER
import rawhttp.core.HttpVersion.HTTP_1_1
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.RequestLine
import java.net.URI

fun get(
    host: String,
    validateCertificates: Boolean = true,
    clientCertificate: Certificate? = null
): RawHttpResponse<*> {
    val client = HttpClient(
        https = true,
        host = host,
        port = 443,
        readTimeout = 5000,
        keepAliveTimeout = 15000,
        maxParallelRequests = 5,
        prepareRequest = { it },
        onRequest = {},
        onResponse = { _, _ -> },
        validateCertificates = validateCertificates,
        clientCertificate = clientCertificate
    )

    return client.send(
        RawHttpRequest(
            RequestLine("GET", URI("/"), HTTP_1_1),
            RawHttpHeaders.newBuilder().with(HOST_HEADER, "$host:443").build(),
            null,
            null
        )
    )
}