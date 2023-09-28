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
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.ParsedMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.toByteArray
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.transport
import com.exactpro.th2.common.utils.event.toTransport
import com.exactpro.th2.common.utils.event.transport.toProto
import com.exactpro.th2.common.utils.message.transport.getList
import com.exactpro.th2.common.utils.message.transport.getString
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
import rawhttp.core.body.BytesBody
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Instant
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Message as TransportMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageGroup as TransportMessageGroup
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage as TransportRawMessage

internal const val REQUEST_MESSAGE = "Request"

private const val METHOD_FIELD = "method"
private const val URI_FIELD = "uri"
private const val HEADERS_FIELD = "headers"
private const val HEADER_NAME_FIELD = "name"
private const val HEADER_VALUE_FIELD = "value"
private const val HEADER_PREFIX = "header-"

private const val TH2_REQUEST_ID_PROPERTY = "th2-request-id"
private const val METHOD_PROPERTY = METHOD_FIELD
private const val URI_PROPERTY = URI_FIELD
private const val CONTENT_TYPE_PROPERTY = "contentType"

private const val DEFAULT_METHOD = "GET"
private const val DEFAULT_URI = "/"

private const val CONTENT_TYPE_HEADER = "Content-Type"
const val CONTENT_LENGTH_HEADER = "Content-Length"
private const val HEADER_VALUE_SEPARATOR = ";"

private fun IRequest.toHttpRequest(): RawHttpRequest {
    val method = (getString(METHOD_FIELD) ?: metadata[METHOD_PROPERTY])?.uppercase() ?: DEFAULT_METHOD
    val uri = getString(URI_FIELD) ?: metadata[URI_PROPERTY] ?: DEFAULT_URI

    val httpRequestLine = RequestLine(method, URI(uri), HTTP_1_1)
    val httpHeaders = createHeaders()

    val httpBody = raw.takeIf(ByteArray::isNotEmpty)?.run {
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

    metadataProperties.forEach { (name, value) ->
        if (name.startsWith(HEADER_PREFIX, ignoreCase = true))
            httpHeaders.with(name.substring(HEADER_PREFIX.length), value)
    }

    return Th2RawHttpRequest(httpRequestLine, httpHeaders.build(), httpBody, null, parentEventId, metadataProperties)
}

private fun Message.requireType(type: String): Message = apply {
    check(metadata.messageType == type) { "Invalid message type: ${metadata.messageType} (expected: $type)" }
}

private fun ParsedMessage.requireType(type: String): ParsedMessage = apply {
    check(this@requireType.type == type) { "Invalid message type: ${this@requireType.type} (expected: $type)" }
}

private fun AnyMessage.toParsed(name: String): Message = run {
    require(hasMessage()) { "$name is not a parsed message: ${toPrettyString()}" }
    message
}

private fun AnyMessage.toRaw(name: String): RawMessage = run {
    require(hasRawMessage()) { "$name is not a raw message: ${toPrettyString()}" }
    rawMessage
}

internal inline fun <reified T : TransportMessage<*>> TransportMessage<*>.cast(name: String): T =
    with(T::class.javaObjectType) {
        require(isInstance(this@cast)) { "$name is not a parsed message: $this" }
        cast(this@cast)
    }

fun MessageGroup.toRequest(): RawHttpRequest = when (messagesCount) {
    0 -> error("Message group is empty")
    1 -> getMessages(0).run {
        when {
            hasMessage() -> ProtoRequest(message.requireType(REQUEST_MESSAGE)).toHttpRequest()
            hasRawMessage() -> ProtoRequest(rawMessage).toHttpRequest()
            else -> error("Single message in group is neither parsed nor raw: ${toPrettyString()}")
        }
    }

    2 -> {
        val head = getMessages(0).toParsed("Head").requireType(REQUEST_MESSAGE)
        val body = getMessages(1).toRaw("Body")
        ProtoRequest(head, body).toHttpRequest()
    }

    else -> error("Message group contains more than 2 messages")
}

fun TransportMessageGroup.toRequest(): RawHttpRequest = when (messages.size) {
    0 -> error("Message group is empty")
    1 -> messages.first().run {
        when (this) {
            is ParsedMessage -> TransportRequest(requireType(REQUEST_MESSAGE)).toHttpRequest()
            is TransportRawMessage -> TransportRequest(this).toHttpRequest()
            else -> error("Single message in group is neither parsed nor raw: $this")
        }
    }

    2 -> {
        val head = messages[0].cast<ParsedMessage>("Head").requireType(REQUEST_MESSAGE)
        val body = messages[1].cast<TransportRawMessage>("Body")
        TransportRequest(head, body).toHttpRequest()
    }

    else -> error("Message group contains more than 2 messages")
}

private sealed interface IRequest {
    val parentEventId: EventID?
    val metadata: Map<String, String>
    val metadataProperties: Map<String, String>
    val raw: ByteArray

    fun getString(field: String): String?
    fun createHeaders(): RawHttpHeaders.Builder
}

private class ProtoRequest(
    private val head: Message,
    private val body: RawMessage,
) : IRequest {
    override val parentEventId: EventID?
        get() = if (head.hasParentEventId()) head.parentEventId else body.parentEventId
    override val metadata: Map<String, String> = body.metadata.propertiesMap
    override val metadataProperties: Map<String, String>
        get() = metadata + head.metadata.propertiesMap
    override val raw: ByteArray
        get() = body.body.toByteArray()

    constructor(head: Message) : this(head, RawMessage.getDefaultInstance())
    constructor(body: RawMessage) : this(Message.getDefaultInstance(), body)

    override fun getString(field: String): String? = head.getString(field)
    override fun createHeaders(): RawHttpHeaders.Builder = RawHttpHeaders.newBuilder().apply {
        head.getList(HEADERS_FIELD)?.forEach {
            require(it.hasMessageValue()) { "Item of '$HEADERS_FIELD' field list is not a message: ${it.toPrettyString()}" }
            val message = it.messageValue
            val name = message.getString(HEADER_NAME_FIELD)
                ?: error("Header message has no $HEADER_NAME_FIELD field: ${message.toPrettyString()}")
            val value = message.getString(HEADER_VALUE_FIELD)
                ?: error("Header message has no $HEADER_VALUE_FIELD field: ${message.toPrettyString()}")
            with(name, value)
        }
    }
}

private class TransportRequest(
    private val head: ParsedMessage,
    private val body: TransportRawMessage,
) : IRequest {
    override val parentEventId: EventID?
        get() = (head.eventId ?: body.eventId)?.toProto()
    override val metadata: Map<String, String>
        get() = body.metadata
    override val metadataProperties: Map<String, String>
        get() = metadata + head.metadata
    override val raw: ByteArray
        get() = body.body.toByteArray()

    constructor(head: ParsedMessage) : this(head, TransportRawMessage.EMPTY)
    constructor(body: TransportRawMessage) : this(ParsedMessage.EMPTY, body)

    override fun getString(field: String): String? = head.body.getString(field)

    override fun createHeaders(): RawHttpHeaders.Builder = RawHttpHeaders.newBuilder().apply {
        head.body.getList(HEADERS_FIELD)?.forEach { message ->
            require(message is Map<*, *>) { "Item of '$HEADERS_FIELD' field list is not a message: $message" }
            val name = message.getString(HEADER_NAME_FIELD)
                ?: error("Header message has no $HEADER_NAME_FIELD field: $message")
            val value = message.getString(HEADER_VALUE_FIELD)
                ?: error("Header message has no $HEADER_VALUE_FIELD field: $message")
            with(name, value)
        }
    }

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
    setBody(toByteArray())
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
    is Th2RawHttpRequest -> metadataProperties.toMutableMap().apply { put(TH2_REQUEST_ID_PROPERTY, th2RequestId) }
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