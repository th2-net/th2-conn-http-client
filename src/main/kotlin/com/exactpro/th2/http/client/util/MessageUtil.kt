/*
 * Copyright 2020-2023 Exactpro (Exactpro Systems Limited)
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
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.getList
import com.exactpro.th2.common.message.getString
import com.exactpro.th2.common.message.toTimestamp
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.transport
import com.exactpro.th2.common.utils.event.toTransport
import com.exactpro.th2.http.client.api.decorators.Th2RawHttpRequest
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite.Builder
import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.util.JsonFormat
import io.netty.buffer.Unpooled
import rawhttp.core.HttpMessage
import rawhttp.core.HttpVersion.HTTP_1_1
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.RequestLine
import rawhttp.core.body.BytesBody
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Instant
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage as TransportRawMessage

private const val REQUEST_MESSAGE = "Request"

private const val METHOD_FIELD = "method"
private const val URI_FIELD = "uri"
private const val HEADERS_FIELD = "headers"
private const val HEADER_NAME_FIELD = "name"
private const val HEADER_VALUE_FIELD = "value"
private const val HEADER_PREFIX = "header-"

private const val METHOD_PROPERTY = METHOD_FIELD
private const val URI_PROPERTY = URI_FIELD
private const val CONTENT_TYPE_PROPERTY = "contentType"

private const val DEFAULT_METHOD = "GET"
private const val DEFAULT_URI = "/"

private const val CONTENT_TYPE_HEADER = "Content-Type"
const val CONTENT_LENGTH_HEADER = "Content-Length"
private const val HEADER_VALUE_SEPARATOR = ";"

private fun createRequest(head: Message, body: RawMessage): RawHttpRequest {
    val metadata = body.metadata.propertiesMap
    val method = (head.getString(METHOD_FIELD) ?: metadata[METHOD_PROPERTY])?.uppercase() ?: DEFAULT_METHOD
    val uri = head.getString(URI_FIELD) ?: metadata[URI_PROPERTY] ?: DEFAULT_URI

    val httpRequestLine = RequestLine(method, URI(uri), HTTP_1_1)
    val httpHeaders = RawHttpHeaders.newBuilder()

    head.getList(HEADERS_FIELD)?.forEach {
        require(it.hasMessageValue()) { "Item of '$HEADERS_FIELD' field list is not a message: ${it.toPrettyString()}" }
        val message = it.messageValue
        val name = message.getString(HEADER_NAME_FIELD)
            ?: error("Header message has no $HEADER_NAME_FIELD field: ${message.toPrettyString()}")
        val value = message.getString(HEADER_VALUE_FIELD)
            ?: error("Header message has no $HEADER_VALUE_FIELD field: ${message.toPrettyString()}")
        httpHeaders.with(name, value)
    }

    val httpBody = body.body.toByteArray().takeIf(ByteArray::isNotEmpty)?.run {
        if (CONTENT_TYPE_HEADER !in httpHeaders.headerNames) {
            metadata[CONTENT_TYPE_PROPERTY]?.run {
                split(HEADER_VALUE_SEPARATOR).forEach {
                    httpHeaders.with(CONTENT_TYPE_HEADER, it.trim())
                }
            }
        }

        if (CONTENT_LENGTH_HEADER !in httpHeaders.headerNames) {
            httpHeaders.with(CONTENT_LENGTH_HEADER, size.toString())
        }

        BytesBody(this).toBodyReader().eager()
    }

    val parentEventId = if (head.hasParentEventId()) head.parentEventId else body.parentEventId
    val metadataProperties = body.metadata.propertiesMap + head.metadata.propertiesMap

    metadataProperties.forEach { (name, value) ->
        if (name.startsWith(HEADER_PREFIX, ignoreCase = true))
            httpHeaders.with(name.substring(HEADER_PREFIX.length), value)
    }

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

fun MessageGroup.toRequest(): RawHttpRequest = when (messagesCount) {
    0 -> error("Message group is empty")
    1 -> getMessages(0).run {
        when {
            hasMessage() -> createRequest(message.requireType(REQUEST_MESSAGE), RawMessage.getDefaultInstance())
            hasRawMessage() -> createRequest(Message.getDefaultInstance(), rawMessage)
            else -> error("Single message in group is neither parsed nor raw: ${toPrettyString()}")
        }
    }

    2 -> {
        val head = getMessages(0).toParsed("Head").requireType(REQUEST_MESSAGE)
        val body = getMessages(1).toRaw("Body")
        createRequest(head, body)
    }

    else -> error("Message group contains more than 2 messages")
}

private inline operator fun <T : Builder> T.invoke(block: T.() -> Unit) = apply(block)

fun MessageOrBuilder.toPrettyString(): String =
    JsonFormat.printer().omittingInsignificantWhitespace().includingDefaultValueFields().print(this)

fun RawMessage.Builder.toBatch(): MessageGroupBatch = run(AnyMessage.newBuilder()::setRawMessage)
    .run(MessageGroup.newBuilder()::addMessages)
    .run(MessageGroupBatch.newBuilder()::addGroups)
    .build()

private fun ByteArrayOutputStream.toProtoMessage(
    connectionId: ConnectionID,
    direction: Direction,
    sequence: Long,
    metadataProperties: Map<String, String>,
    parentEventId: EventID? = null
) = RawMessage.newBuilder().apply {
    parentEventId?.let(this::setParentEventId)
    this.body = ByteString.copyFrom(toByteArray())
    this.metadataBuilder {
        putAllProperties(metadataProperties)
        this.idBuilder {
            this.connectionId = connectionId
            this.direction = direction
            this.sequence = sequence
            timestamp = Instant.now().toTimestamp()
        }
    }
}

private fun ByteArrayOutputStream.toTransportMessage(
    sessionAlias: String,
    direction: Direction,
    sequence: Long,
    metadataProperties: Map<String, String>,
    parentEventId: EventID? = null
): TransportRawMessage.Builder = TransportRawMessage.builder().apply {
    parentEventId?.let {
        setEventId(parentEventId.toTransport())
    }
    setBody(Unpooled.wrappedBuffer(toByteArray()))
    setMetadata(metadataProperties)
    idBuilder().apply {
        setSessionAlias(sessionAlias)
        setDirection(direction.transport)
        setSequence(sequence)
        setTimestamp(Instant.now())
    }
}

private val RawHttpRequest.parentEventId: EventID?
    get() = when (this) {
        is Th2RawHttpRequest -> parentEventId
        else -> null
    }

private fun HttpMessage.toByteArrayOutputStream(): ByteArrayOutputStream {
    return ByteArrayOutputStream().apply {
        startLine.writeTo(this)
        headers.writeTo(this)
        body.ifPresent { it.writeTo(this) }
    }
}

private fun RawHttpRequest.toProperties(): Map<String, String> = when (this) {
    is Th2RawHttpRequest -> metadataProperties.toMutableMap()
    else -> hashMapOf()
}.apply {
    put(METHOD_PROPERTY, method)
    put(URI_PROPERTY, uri.toString())

    if (CONTENT_TYPE_HEADER in headers) {
        put(CONTENT_TYPE_PROPERTY, headers[CONTENT_TYPE_HEADER].joinToString(HEADER_VALUE_SEPARATOR))
    }
}

private fun HttpMessage.toProtoMessage(
    connectionId: ConnectionID,
    direction: Direction,
    sequence: Long,
    request: RawHttpRequest
): RawMessage.Builder = toByteArrayOutputStream().toProtoMessage(
    connectionId,
    direction,
    sequence,
    request.toProperties(),
    request.parentEventId
)

fun RawHttpRequest.toProtoMessage(connectionId: ConnectionID, sequence: Long): RawMessage.Builder =
    toProtoMessage(connectionId, SECOND, sequence, this)

fun RawHttpResponse<*>.toProtoMessage(
    connectionId: ConnectionID,
    sequence: Long,
    request: RawHttpRequest
): RawMessage.Builder = toProtoMessage(connectionId, FIRST, sequence, request)

private fun HttpMessage.toTransportMessage(
    sessionAlias: String,
    direction: Direction,
    sequence: Long,
    request: RawHttpRequest
): TransportRawMessage.Builder = toByteArrayOutputStream().toTransportMessage(
    sessionAlias,
    direction,
    sequence,
    request.toProperties(),
    request.parentEventId
)

fun RawHttpRequest.toTransportMessage(sessionAlias: String, sequence: Long): TransportRawMessage.Builder =
    toTransportMessage(sessionAlias, SECOND, sequence, this)

fun RawHttpResponse<*>.toTransportMessage(
    sessionAlias: String,
    sequence: Long,
    request: RawHttpRequest
): TransportRawMessage.Builder = toTransportMessage(sessionAlias, FIRST, sequence, request)

val MessageGroup.eventIds: Sequence<String>
    get() = messagesList.asSequence()
        .map { if (it.hasMessage()) it.message.parentEventId.id else it.rawMessage.parentEventId.id }
        .filter(String::isNotBlank)