/*
 * Copyright 2023-2026 Exactpro (Exactpro Systems Limited)
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

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.message.MessageListener
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.common.schema.message.QueueAttribute.RAW
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.GroupBatch
import com.exactpro.th2.common.utils.event.EventBatcher
import com.exactpro.th2.common.utils.event.storeEvent
import com.exactpro.th2.common.utils.event.transport.toProto
import com.exactpro.th2.common.utils.message.RAW_GROUP_SELECTOR
import com.exactpro.th2.common.utils.message.RawMessageBatcher
import com.exactpro.th2.common.utils.message.parentEventIds
import com.exactpro.th2.common.utils.message.sessionAlias
import com.exactpro.th2.common.utils.message.transport.MessageBatcher
import com.exactpro.th2.common.utils.message.transport.MessageBatcher.Companion.GROUP_SELECTOR
import com.exactpro.th2.common.utils.message.transport.eventIds
import com.exactpro.th2.common.utils.shutdownGracefully
import com.exactpro.th2.http.client.api.IRequestHandler
import com.exactpro.th2.http.client.api.IRequestHandler.RequestHandlerContext
import com.exactpro.th2.http.client.api.IStateManager
import com.exactpro.th2.http.client.api.IStateManager.StateManagerContext
import com.exactpro.th2.http.client.api.impl.BasicRequestHandler
import com.exactpro.th2.http.client.api.impl.BasicStateManager
import com.exactpro.th2.http.client.util.toPrettyString
import com.exactpro.th2.http.client.util.toProtoMessage
import com.exactpro.th2.http.client.util.toTransportMessage
import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.time.Instant
import java.util.ServiceLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val SEND_PIN_ATTRIBUTE = "send"
internal const val INPUT_QUEUE_TRANSPORT_ATTRIBUTE = SEND_PIN_ATTRIBUTE
private val INPUT_QUEUE_PROTO_ATTRIBUTES = arrayOf(SEND_PIN_ATTRIBUTE, "group")

private data class Holder(
    val service: ExecutorService,
    val handler: IRequestHandler,
    val clientEventId: EventID,
)

class Application(
    factory: CommonFactory,
    private val registerResource: (name: String, destructor: () -> Unit) -> Unit,
) {
    private val settings: Settings = getSettings(factory::getCustomConfiguration)
    private val eventRouter: MessageRouter<EventBatch> = factory.eventBatchRouter
    private val protoMR: MessageRouter<MessageGroupBatch> = factory.messageRouterMessageGroupBatch
    private val transportMR: MessageRouter<GroupBatch> = factory.transportGroupBatchRouter
    private val rootEventId: EventID = factory.rootEventId

    fun start() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        registerResource("message batch executor") { executor.shutdownGracefully() }

        val book = rootEventId.bookName
        with(settings) {
            val eventBatcher = EventBatcher(
                maxBatchSizeInItems = maxBatchSize,
                executor = executor,
                maxFlushTime = maxFlushTime,
                onBatch = eventRouter::send
            ).also { registerResource("event batcher", it::close) }

            val onError: (Throwable) -> Unit = {
                eventBatcher.storeEvent(rootEventId, "Batching problem: ${it.message}", "Message batching problem", it)
            }

            lateinit var transportMB: MessageBatcher
            lateinit var protoMB: RawMessageBatcher
            if (useTransport) {
                transportMB =
                    MessageBatcher(
                        maxBatchSize,
                        maxFlushTime,
                        book,
                        GROUP_SELECTOR,
                        executor,
                        onError,
                        transportMR::send
                    ).also { registerResource("transport message batcher", it::close) }
            } else {
                protoMB = RawMessageBatcher(maxBatchSize, maxFlushTime, RAW_GROUP_SELECTOR, executor, onError) {
                    protoMR.send(it, RAW.value)
                }.also { registerResource("proto message batcher", it::close) }
            }

            val aliasToService = mutableMapOf<String, Holder>()
            sessions.forEach { sessionAlias, sessionSettings ->
                val stateManager = load<IStateManager>(BasicStateManager::class.java)
                    .also { registerResource("state manager $sessionAlias", it::close) }
                val requestHandler = load<IRequestHandler>(BasicRequestHandler::class.java)
                    .also { registerResource("request handler $sessionAlias", it::close) }
                val sessionGroup = sessionAlias
                val clientEventId = Event.start()
                    .name("Client: $sessionAlias")
                    .type("ClientEvent")
                    .toBatchProto(rootEventId)
                    .also(eventRouter::send)
                    .getEvents(0).id

                // component supported multithreading sending via single http client.
                // increment sequence and putting into message batcher should be executed atomically.
                val incomingLock = ReentrantLock()
                val outgoingLock = ReentrantLock()
                val incomingSequence = createSequence()
                val outgoingSequence = createSequence()

                val onRequest: (RawHttpRequest) -> Unit
                val onResponse: (RawHttpRequest, RawHttpResponse<*>) -> Unit

                if (useTransport) {
                    onRequest = { request: RawHttpRequest ->
                        val rawMessage = outgoingLock.withLock {
                            request.toTransportMessage(sessionAlias, outgoingSequence()).also {
                                transportMB.onMessage(it, sessionGroup)
                            }
                        }
                        eventBatcher.storeEvent(
                            rawMessage.eventId?.toProto() ?: rootEventId,
                            "Sent HTTP request",
                            "Send message"
                        )
                    }
                    onResponse = { request: RawHttpRequest, response: RawHttpResponse<*> ->
                        incomingLock.withLock {
                            transportMB.onMessage(
                                response.toTransportMessage(sessionAlias, incomingSequence(), request),
                                sessionGroup
                            )
                        }
                        stateManager.onResponse(response)
                    }
                } else {
                    val connectionId = com.exactpro.th2.common.grpc.ConnectionID.newBuilder()
                        .setSessionAlias(sessionAlias)
                        .setSessionGroup(sessionGroup)
                        .build()

                    onRequest = { request: RawHttpRequest ->
                        val rawMessage = outgoingLock.withLock {
                            request.toProtoMessage(connectionId, outgoingSequence())
                                .also(protoMB::onMessage)
                        }
                        eventBatcher.storeEvent(
                            if (rawMessage.hasParentEventId()) rawMessage.parentEventId else rootEventId,
                            "Sent HTTP request",
                            "Send message"
                        )
                    }
                    onResponse = { request: RawHttpRequest, response: RawHttpResponse<*> ->
                        incomingLock.withLock {
                            protoMB.onMessage(
                                response.toProtoMessage(connectionId, incomingSequence(), request)
                            )
                        }
                        stateManager.onResponse(response)
                    }
                }

                with(sessionSettings) {
                    val client = HttpClient(
                        https,
                        host,
                        port,
                        readTimeout,
                        keepAliveTimeout,
                        maxParallelRequests,
                        defaultHeaders,
                        stateManager::prepareRequest,
                        onRequest,
                        onResponse,
                        stateManager::onStart,
                        stateManager::onStop,
                        validateCertificates,
                        certificate
                    ).apply { registerResource("client-$sessionAlias", ::close) }

                    stateManager.runCatching {
                        registerResource("state-manager-$sessionAlias", ::close)
                        init(StateManagerContext(client, auth))
                    }.onFailure {
                        LOGGER.error(it) { "Failed to init state manager for client: $sessionAlias" }
                        eventBatcher.storeEvent(clientEventId, "Failed to init state manager for client: $sessionAlias", "Error", it)
                        throw it
                    }

                    requestHandler.runCatching {
                        registerResource("request-handler-$sessionAlias", ::close)
                        init(RequestHandlerContext(client))
                    }.onFailure {
                        LOGGER.error(it) { "Failed to init request handler for client: $sessionAlias" }
                        eventBatcher.storeEvent(clientEventId, "Failed to init request handler for client: $sessionAlias", "Error", it)
                        throw it
                    }

                    aliasToService[sessionAlias] = Holder(
                        createExecutorService(maxParallelRequests),
                        requestHandler,
                        clientEventId,
                    )

                    client.runCatching(HttpClient::start).onFailure {
                        throw IllegalStateException("Failed to start client: $sessionAlias", it)
                    }
                }
            }

            val proto = runCatching {
                val listener = MessageListener<MessageGroupBatch> { _, message ->
                    message.groupsList.forEach { group ->
                        val alias = group.sessionAlias
                        aliasToService[alias]?.let { holder ->
                            holder.service.submit {
                                group.runCatching(holder.handler::onRequest).recoverCatching { error ->
                                    LOGGER.error(error) { "Failed to handle protobuf message group: ${group.toPrettyString()}" }
                                    group.parentEventIds.ifEmpty { sequenceOf(holder.clientEventId) }.forEach {
                                        eventBatcher.storeEvent(
                                            it,
                                            "Failed to handle protobuf message group",
                                            "Error",
                                            error
                                        )
                                    }
                                }
                            }
                        } ?: run {
                            LOGGER.error { "'$alias' session alias isn't in serviced, group: ${group.toPrettyString()}" }
                            eventBatcher.storeEvent(
                                rootEventId,
                                "Failed to handle protobuf message group: '$alias' session alias isn't in serviced",
                                "Error",
                            )
                        }
                    }
                }
                checkNotNull(protoMR.subscribe(listener, *INPUT_QUEUE_PROTO_ATTRIBUTES))
            }.onSuccess { monitor ->
                registerResource("proto-raw-monitor", monitor::unsubscribe)
            }.onFailure {
                LOGGER.warn(it) { "Failed to subscribe to input protobuf queue" }
            }

            val transport = runCatching {
                val listener = MessageListener<GroupBatch> { _, batch ->
                    batch.groups.forEach { group ->
                        val alias = group.messages[0].id.sessionAlias
                        aliasToService[alias]?.let { holder ->
                            holder.service.submit {
                                group.runCatching(holder.handler::onRequest).recoverCatching { error ->
                                    LOGGER.error(error) { "Failed to handle transport message group: $group" }
                                    group.eventIds.map(com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.EventId::toProto)
                                        .ifEmpty { sequenceOf(holder.clientEventId) }.forEach {
                                            eventBatcher.storeEvent(
                                                it,
                                                "Failed to handle transport message group",
                                                "Error",
                                                error
                                            )
                                        }
                                }
                            }
                        } ?: run {
                            LOGGER.error { "'$alias' session alias isn't in serviced, group: $group" }
                            eventBatcher.storeEvent(
                                rootEventId,
                                "Failed to handle protobuf message group: '$alias' session alias isn't in serviced",
                                "Error",
                            )
                        }
                    }
                }
                checkNotNull(transportMR.subscribe(listener, INPUT_QUEUE_TRANSPORT_ATTRIBUTE))
            }.onSuccess { monitor ->
                registerResource("transport-raw-monitor", monitor::unsubscribe)
            }.onFailure {
                LOGGER.warn(it) { "Failed to subscribe to input transport queue" }
            }

            if (proto.isFailure && transport.isFailure) {
                error("Subscribe pin should be declared at least one of protobuf or transport protocols")
            }
        }
    }

    companion object {
        private val LOGGER = KotlinLogging.logger { }
    }
}

inline fun <reified T> load(defaultImpl: Class<out T>): T {
    val instances = ServiceLoader.load(T::class.java).toList()

    return when (instances.size) {
        0 -> error("No instances of ${T::class.simpleName}")
        1 -> instances.first()
        2 -> instances.first { !defaultImpl.isInstance(it) }
        else -> error("More than 1 non-default instance of ${T::class.simpleName} has been found: $instances")
    }
}

private fun createSequence(): () -> Long = Instant.now().run {
    AtomicLong(epochSecond * SECONDS.toNanos(1) + nano)
}::incrementAndGet

private fun createExecutorService(maxCount: Int): ExecutorService =
    Executors.newFixedThreadPool(maxCount, ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat("th2-http-client-%d")
        .build())