/*
 * Copyright 2020-2022 Exactpro (Exactpro Systems Limited)
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

@file:JvmName("MessageUtil")

package com.exactpro.th2.http.client.util

import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.Direction.FIRST
import com.exactpro.th2.common.grpc.Direction.SECOND
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.getList
import com.exactpro.th2.common.message.getString
import com.exactpro.th2.common.message.toTimestamp
import com.exactpro.th2.http.client.api.decorators.Th2RawHttpRequest
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite.Builder
import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.util.JsonFormat
import rawhttp.core.HttpMessage
import rawhttp.core.HttpVersion.HTTP_1_1
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.RequestLine
import rawhttp.core.UriUtil
import rawhttp.core.body.EagerBodyReader
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Instant

private const val REQUEST_MESSAGE = "Request"

private const val METHOD_FIELD = "method"
private const val URI_FIELD = "uri"
private const val HEADERS_FIELD = "headers"
private const val HEADER_NAME_FIELD = "name"
private const val HEADER_VALUE_FIELD = "value"

private const val METHOD_PROPERTY = METHOD_FIELD
private const val URI_PROPERTY = URI_FIELD
private const val CONTENT_TYPE_PROPERTY = "contentType"

private const val DEFAULT_METHOD = "GET"
private const val DEFAULT_URI = "/"

private const val CONTENT_TYPE_HEADER = "Content-Type"
const val CONTENT_LENGTH_HEADER = "Content-Length"
const val HOST_HEADER = "Host"
private const val HEADER_VALUE_SEPARATOR = ";"

private fun createRequest(head: Message, body: RawMessage, host: String, defaultHeaders: Map<String, List<String>>): RawHttpRequest {
    val metadata = body.metadata.propertiesMap
    val method = (head.getString(METHOD_FIELD) ?: metadata[METHOD_PROPERTY])?.uppercase() ?: DEFAULT_METHOD
    val uri = head.getString(URI_FIELD) ?: metadata[URI_PROPERTY] ?: DEFAULT_URI

    val httpRequestLine = RequestLine(method, UriUtil.withHost(URI(uri), host), HTTP_1_1)
    val httpHeaders = RawHttpHeaders.newBuilder()

    head.getList(HEADERS_FIELD)?.forEach {
        require(it.hasMessageValue()) { "Item of '$HEADERS_FIELD' field list is not a message: ${it.toPrettyString()}" }
        val message = it.messageValue
        val name = message.getString(HEADER_NAME_FIELD) ?: error("Header message has no $HEADER_NAME_FIELD field: ${message.toPrettyString()}")
        val value = message.getString(HEADER_VALUE_FIELD) ?: error("Header message has no $HEADER_VALUE_FIELD field: ${message.toPrettyString()}")
        httpHeaders.with(name, value)
    }

    val httpBody = body.body.toByteArray().takeIf(ByteArray::isNotEmpty)?.run {
        val headerNames = httpHeaders.headerNames

        if (CONTENT_TYPE_HEADER !in headerNames) {
            metadata[CONTENT_TYPE_PROPERTY]?.run {
                split(HEADER_VALUE_SEPARATOR).forEach {
                    httpHeaders.with(CONTENT_TYPE_HEADER, it.trim())
                }
            }
        }

        if (CONTENT_LENGTH_HEADER !in headerNames) {
            httpHeaders.with(CONTENT_LENGTH_HEADER, size.toString())
        }

        if (HOST_HEADER !in headerNames) {
            httpHeaders.with(HOST_HEADER, host)
        }

        defaultHeaders.forEach { key, values ->
            if (!headerNames.contains(key)) {
                values.forEach { httpHeaders.with(key, it) }
            }
        }

        EagerBodyReader(this)
    }

    val parentEventId = head.parentEventId.id.ifEmpty { body.parentEventId.id }
    val metadataProperties = body.metadata.propertiesMap + head.metadata.propertiesMap

    return Th2RawHttpRequest(httpRequestLine, httpHeaders.build(), httpBody, null, parentEventId, metadataProperties)
}

private fun Message.requireType(type: String): Message = apply {
    check(metadata.messageType == type) { "Invalid message type: ${metadata.messageType} (expected: $type)" }
}

private fun AnyMessage.toParsed(name: String): Message = run {
    require(hasMessage()) { "$name is not a parsed message: ${toPrettyString()}" }
    message
}

private fun AnyMessage.toRaw(name: String): RawMessage = run {
    require(hasRawMessage()) { "$name is not a raw message: ${toPrettyString()}" }
    rawMessage
}

fun MessageGroup.toRequest(host: String, defaultHeaders: Map<String, List<String>>): RawHttpRequest = when (messagesCount) {
    0 -> error("Message group is empty")
    1 -> getMessages(0).run {
        when {
            hasMessage() -> createRequest(message.requireType(REQUEST_MESSAGE), RawMessage.getDefaultInstance(), host, defaultHeaders)
            hasRawMessage() -> createRequest(Message.getDefaultInstance(), rawMessage, host, defaultHeaders)
            else -> error("Single message in group is neither parsed nor raw: ${toPrettyString()}")
        }
    }
    2 -> {
        val head = getMessages(0).toParsed("Head").requireType(REQUEST_MESSAGE)
        val body = getMessages(1).toRaw("Body")
        createRequest(head, body, host, defaultHeaders)
    }
    else -> error("Message group contains more than 2 messages")
}

private inline operator fun <T : Builder> T.invoke(block: T.() -> Unit) = apply(block)

fun MessageOrBuilder.toPrettyString(): String = JsonFormat.printer().omittingInsignificantWhitespace().includingDefaultValueFields().print(this)

fun RawMessage.Builder.toBatch(): MessageGroupBatch = run(AnyMessage.newBuilder()::setRawMessage)
    .run(MessageGroup.newBuilder()::addMessages)
    .run(MessageGroupBatch.newBuilder()::addGroups)
    .build()

private fun ByteArrayOutputStream.toRawMessage(
    connectionId: ConnectionID,
    direction: Direction,
    sequence: Long,
    metadataProperties: Map<String, String>,
    parentEventId: String? = null
) = RawMessage.newBuilder().apply {
    parentEventId?.let(parentEventIdBuilder::setId)
    this.body = ByteString.copyFrom(toByteArray())
    this.metadataBuilder {
        putAllProperties(metadataProperties)
        this.timestamp = Instant.now().toTimestamp()
        this.idBuilder {
            this.connectionId = connectionId
            this.direction = direction
            this.sequence = sequence
        }
    }
}

private fun HttpMessage.toRawMessage(connectionId: ConnectionID, direction: Direction, sequence: Long, request: RawHttpRequest): RawMessage.Builder {
    val (metadataProperties, parentEventId) = when (request) {
        is Th2RawHttpRequest -> request.metadataProperties.toMutableMap() to request.parentEventId
        else -> mutableMapOf<String, String>() to null
    }

    metadataProperties[METHOD_PROPERTY] = request.method
    metadataProperties[URI_PROPERTY] = request.uri.toString()

    if (CONTENT_TYPE_HEADER in headers) {
        metadataProperties[CONTENT_TYPE_PROPERTY] = headers[CONTENT_TYPE_HEADER].joinToString(HEADER_VALUE_SEPARATOR)
    }

    return ByteArrayOutputStream().run {
        startLine.writeTo(this)
        headers.writeTo(this)
        body.ifPresent { it.writeTo(this) }
        toRawMessage(connectionId, direction, sequence, metadataProperties, parentEventId)
    }
}

fun RawHttpRequest.toRawMessage(connectionId: ConnectionID, sequence: Long): RawMessage.Builder = toRawMessage(connectionId, SECOND, sequence, this)
fun RawHttpResponse<*>.toRawMessage(connectionId: ConnectionID, sequence: Long, request: RawHttpRequest): RawMessage.Builder = toRawMessage(connectionId, FIRST, sequence, request)
