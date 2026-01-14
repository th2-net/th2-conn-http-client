/*
 * Copyright 2021-2026 Exactpro (Exactpro Systems Limited)
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

import com.exactpro.th2.common.grpc.Event
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.EventStatus
import com.exactpro.th2.common.schema.box.configuration.BoxConfiguration.DEFAULT_BOOK_NAME
import com.exactpro.th2.http.client.util.Certificate
import com.exactpro.th2.test.queue.CollectorMessageListener
import rawhttp.core.HttpVersion.HTTP_1_1
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.RequestLine
import strikt.api.expectThat
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.matches
import strikt.assertions.single
import java.net.URI
import java.time.Duration.ofSeconds
import kotlin.test.assertNotNull

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
        defaultHeaders = mapOf(),
        prepareRequest = { it },
        onRequest = {},
        onResponse = { _, _ -> },
        validateCertificates = validateCertificates,
        clientCertificate = clientCertificate
    )

    return client.send(
        RawHttpRequest(
            RequestLine("GET", URI("/"), HTTP_1_1),
            RawHttpHeaders.newBuilder()
                .with("Host", host)
                .build(),
            null,
            null
        )
    )
}

fun CollectorMessageListener<EventBatch>.assertRootEvent(): Event =
    assertNotNull(poll(ofSeconds(1))).also {
        expectThat(it) {
            get { eventsList }.single() and {
                get { id }.and {
                    get { bookName }.isEqualTo(DEFAULT_BOOK_NAME)
                    get { scope }.isEqualTo("app")
                }
                get { hasParentId() }.isFalse()
                get { name }.matches(Regex("app \\d{4}-[01]\\d-[0-3]\\dT[0-2]\\d:[0-5]\\d:[0-5]\\d\\.\\d+([+-][0-2]\\d:[0-5]\\d|Z) - Root event"))
                get { type }.isEqualTo("Microservice")
                get { status }.isEqualTo(EventStatus.SUCCESS)
            }
        }
    }.getEvents(0)

fun CollectorMessageListener<EventBatch>.assertClientEvent(eventId: EventID, sessionAlias: String): Event =
    assertNotNull(poll(ofSeconds(1))).also {
        expectThat(it) {
            get { eventsList }.single().and {
                get { name }.isEqualTo("Client: $sessionAlias")
                get { type }.isEqualTo("ClientEvent")
                get { status }.isEqualTo(EventStatus.SUCCESS)
                get { id }.and {
                    get { bookName }.isEqualTo(eventId.bookName)
                    get { scope }.isEqualTo(eventId.scope)
                }
                get { parentId }.isEqualTo(eventId)
                get { attachedMessageIdsList }.isEmpty()
                get { body.toString(Charsets.UTF_8) }.isEqualTo("[]")
            }
        }
    }.getEvents(0)