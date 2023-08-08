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

@file:JvmName("Main")

package com.exactpro.th2.http.client

import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.message.MessageListener
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.common.schema.message.QueueAttribute.RAW
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.EventId
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.GroupBatch
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.proto
import com.exactpro.th2.common.utils.event.EventBatcher
import com.exactpro.th2.common.utils.event.storeEvent
import com.exactpro.th2.common.utils.event.transport.toProto
import com.exactpro.th2.common.utils.message.RAW_DIRECTION_SELECTOR
import com.exactpro.th2.common.utils.message.RAW_GROUP_SELECTOR
import com.exactpro.th2.common.utils.message.RawMessageBatcher
import com.exactpro.th2.common.utils.message.direction
import com.exactpro.th2.common.utils.message.parentEventIds
import com.exactpro.th2.common.utils.message.transport.MessageBatcher
import com.exactpro.th2.common.utils.message.transport.MessageBatcher.Companion.ALIAS_SELECTOR
import com.exactpro.th2.common.utils.message.transport.MessageBatcher.Companion.GROUP_SELECTOR
import com.exactpro.th2.common.utils.message.transport.eventIds
import com.exactpro.th2.common.utils.shutdownGracefully
import com.exactpro.th2.http.client.api.IAuthSettings
import com.exactpro.th2.http.client.api.IAuthSettingsTypeProvider
import com.exactpro.th2.http.client.api.IRequestHandler
import com.exactpro.th2.http.client.api.IRequestHandler.RequestHandlerContext
import com.exactpro.th2.http.client.api.IStateManager
import com.exactpro.th2.http.client.api.IStateManager.StateManagerContext
import com.exactpro.th2.http.client.api.impl.AuthSettingsDeserializer
import com.exactpro.th2.http.client.api.impl.BasicAuthSettingsTypeProvider
import com.exactpro.th2.http.client.api.impl.BasicRequestHandler
import com.exactpro.th2.http.client.api.impl.BasicStateManager
import com.exactpro.th2.http.client.util.Certificate
import com.exactpro.th2.http.client.util.CertificateConverter
import com.exactpro.th2.http.client.util.PrivateKeyConverter
import com.exactpro.th2.http.client.util.toPrettyString
import com.exactpro.th2.http.client.util.toProtoMessage
import com.exactpro.th2.http.client.util.toTransportMessage
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import mu.KotlinLogging
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.system.exitProcess

private val LOGGER = KotlinLogging.logger { }
private const val SEND_PIN_ATTRIBUTE = "send"
private const val INPUT_QUEUE_TRANSPORT_ATTRIBUTE = SEND_PIN_ATTRIBUTE
private val INPUT_QUEUE_PROTO_ATTRIBUTES = arrayOf(SEND_PIN_ATTRIBUTE, "group")

fun main(args: Array<String>) = try {
    val resources = ConcurrentLinkedDeque<Pair<String, () -> Unit>>()

    Runtime.getRuntime().addShutdownHook(thread(start = false, name = "shutdown-hook") {
        resources.descendingIterator().forEach { (resource, destructor) ->
            LOGGER.debug { "Destroying resource: $resource" }
            runCatching(destructor).apply {
                onSuccess { LOGGER.debug { "Successfully destroyed resource: $resource" } }
                onFailure { LOGGER.error(it) { "Failed to destroy resource: $resource" } }
            }
        }
    })

    val stateManager = load<IStateManager>(BasicStateManager::class.java)
    val requestHandler = load<IRequestHandler>(BasicRequestHandler::class.java)
    val authSettingsType = load<IAuthSettingsTypeProvider>(BasicAuthSettingsTypeProvider::class.java).type

    val factory = runCatching {
        CommonFactory.createFromArguments(*args)
    }.getOrElse {
        LOGGER.error(it) { "Failed to create common factory with arguments: ${args.joinToString(" ")}" }
        CommonFactory()
    }.apply { resources += "factory" to ::close }

    val mapper = JsonMapper.builder()
        .addModule(
            KotlinModule.Builder()
                .withReflectionCacheSize(512)
                .configure(KotlinFeature.NullToEmptyCollection, false)
                .configure(KotlinFeature.NullToEmptyMap, false)
                .configure(KotlinFeature.NullIsSameAsDefault, true)
                .configure(KotlinFeature.SingletonSupport, false)
                .configure(KotlinFeature.StrictNullChecks, false)
                .build()
        )
        .addModule(
            SimpleModule().addDeserializer(
                IAuthSettings::class.java,
                AuthSettingsDeserializer(authSettingsType)
            )
        )
        .build()

    run(
        factory.getCustomConfiguration(Settings::class.java, mapper),
        factory.eventBatchRouter,
        factory.messageRouterMessageGroupBatch,
        factory.transportGroupBatchRouter,
        stateManager,
        requestHandler,
        factory.rootEventId
    ) { resource, destructor ->
        resources += resource to destructor
    }
} catch (e: Exception) {
    LOGGER.error(e) { "Uncaught exception. Shutting down" }
    exitProcess(1)
}

fun run(
    settings: Settings,
    eventRouter: MessageRouter<EventBatch>,
    protoMR: MessageRouter<MessageGroupBatch>,
    transportMR: MessageRouter<GroupBatch>,
    stateManager: IStateManager,
    requestHandler: IRequestHandler,
    rootEventId: EventID,
    registerResource: (name: String, destructor: () -> Unit) -> Unit,
) {
    val incomingSequence = createSequence()
    val outgoingSequence = createSequence()

    val onRequest: (RawHttpRequest) -> Unit
    val onResponse: (RawHttpRequest, RawHttpResponse<*>) -> Unit

    val executor = Executors.newSingleThreadScheduledExecutor()
    registerResource("message batch executor") { executor.shutdownGracefully() }

    with(settings) {
        val book = rootEventId.bookName
        val sessionGroup = sessionAlias

        val eventBatcher = EventBatcher(
            maxBatchSizeInItems = maxBatchSize,
            executor = executor,
            maxFlushTime = maxFlushTime,
            onBatch = eventRouter::send
        ).also { registerResource("event batcher", it::close) }

        val onError: (Throwable) -> Unit = {
            eventBatcher.storeEvent(rootEventId, "Batching problem: ${it.message}", "Message batching problem", it)
        }

        if (useTransport) {
            val messageBatcher = if (batchByGroup) {
                MessageBatcher(maxBatchSize, maxFlushTime, book, GROUP_SELECTOR, executor, onError, transportMR::send)
            } else {
                MessageBatcher(maxBatchSize, maxFlushTime, book, ALIAS_SELECTOR, executor, onError) {
                    transportMR.send(it, it.groups.first().messages.first().id.direction.proto.toString())
                }
            }.also { registerResource("transport message batcher", it::close) }

            onRequest = { request: RawHttpRequest ->
                val rawMessage = request.toTransportMessage(sessionAlias, outgoingSequence())

                messageBatcher.onMessage(rawMessage, sessionGroup)
                eventBatcher.storeEvent(
                    rawMessage.eventId?.toProto() ?: rootEventId,
                    "Sent HTTP request",
                    "Send message"
                )
            }
            onResponse = { request: RawHttpRequest, response: RawHttpResponse<*> ->
                messageBatcher.onMessage(
                    response.toTransportMessage(sessionAlias, incomingSequence(), request),
                    sessionGroup
                )
                stateManager.onResponse(response)
            }
        } else {
            val connectionId = ConnectionID.newBuilder()
                .setSessionAlias(sessionAlias)
                .setSessionGroup(sessionGroup)
                .build()

            val messageBatcher = if (batchByGroup) {
                RawMessageBatcher(maxBatchSize, maxFlushTime, RAW_GROUP_SELECTOR, executor, onError) {
                    protoMR.send(it, RAW.value)
                }
            } else {
                RawMessageBatcher(maxBatchSize, maxFlushTime, RAW_DIRECTION_SELECTOR, executor, onError) {
                    protoMR.send(it, RAW.value, it.getGroups(0).getMessages(0).direction.toString())
                }
            }.also { registerResource("proto message batcher", it::close) }

            onRequest = { request: RawHttpRequest ->
                val rawMessage = request.toProtoMessage(connectionId, outgoingSequence())

                messageBatcher.onMessage(rawMessage)
                eventBatcher.storeEvent(
                    if (rawMessage.hasParentEventId()) rawMessage.parentEventId else rootEventId,
                    "Sent HTTP request",
                    "Send message"
                )
            }
            onResponse = { request: RawHttpRequest, response: RawHttpResponse<*> ->
                messageBatcher.onMessage(response.toProtoMessage(connectionId, incomingSequence(), request))
                stateManager.onResponse(response)
            }
        }
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
        ).apply { registerResource("client", ::close) }

        stateManager.runCatching {
            registerResource("state-manager", ::close)
            init(StateManagerContext(client, auth))
        }.onFailure {
            LOGGER.error(it) { "Failed to init state manager" }
            eventBatcher.storeEvent(rootEventId, "Failed to init state manager", "Error", it)
            throw it
        }

        requestHandler.runCatching {
            registerResource("request-handler", ::close)
            init(RequestHandlerContext(client))
        }.onFailure {
            LOGGER.error(it) { "Failed to init request handler" }
            eventBatcher.storeEvent(rootEventId, "Failed to init request handler", "Error", it)
            throw it
        }

        val sendService: ExecutorService = createExecutorService(maxParallelRequests)

        val proto = runCatching {
            val listener = MessageListener<MessageGroupBatch> { _, message ->
                message.groupsList.forEach { group ->
                    sendService.submit {
                        group.runCatching(requestHandler::onRequest).recoverCatching { error ->
                            LOGGER.error(error) { "Failed to handle protobuf message group: ${group.toPrettyString()}" }
                            group.parentEventIds.ifEmpty { sequenceOf(rootEventId) }.forEach {
                                eventBatcher.storeEvent(
                                    it,
                                    "Failed to handle protobuf message group",
                                    "Error",
                                    error
                                )
                            }
                        }
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
            val listener = MessageListener<GroupBatch> { _, message ->
                message.groups.forEach { group ->
                    sendService.submit {
                        group.runCatching(requestHandler::onRequest).recoverCatching { error ->
                            LOGGER.error(error) { "Failed to handle transport message group: $group" }
                            group.eventIds.map(EventId::toProto).ifEmpty { sequenceOf(rootEventId) }.forEach {
                                eventBatcher.storeEvent(
                                    it,
                                    "Failed to handle transport message group",
                                    "Error",
                                    error
                                )
                            }
                        }
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

        client.runCatching(HttpClient::start).onFailure {
            throw IllegalStateException("Failed to start client", it)
        }

        LOGGER.info { "Successfully started" }

        ReentrantLock().run {
            val condition = newCondition()
            registerResource("await-shutdown") { withLock(condition::signalAll) }
            withLock(condition::await)
        }

        LOGGER.info { "Finished running" }
    }
}

data class Settings(
    val https: Boolean = false,
    val host: String,
    val port: Int = if (https) 443 else 80,
    val readTimeout: Int = 5000,
    val maxParallelRequests: Int = 5,
    val keepAliveTimeout: Long = 15000,
    val defaultHeaders: Map<String, List<String>> = emptyMap(),
    val sessionAlias: String,
    val auth: IAuthSettings? = null,
    val validateCertificates: Boolean = true,
    val useTransport: Boolean = false,
    val batchByGroup: Boolean = true,
    val batcherThreads: Int = 2,
    val maxBatchSize: Int = 1000,
    val maxFlushTime: Long = 1000,
    @JsonDeserialize(converter = CertificateConverter::class) val clientCertificate: X509Certificate? = null,
    @JsonDeserialize(converter = PrivateKeyConverter::class) val certificatePrivateKey: PrivateKey? = null,
) {
    @JsonIgnore
    val certificate: Certificate? = clientCertificate?.run {
        requireNotNull(certificatePrivateKey) {
            "'${::clientCertificate.name}' setting requires '${::certificatePrivateKey.name}' setting to be set"
        }

        Certificate(clientCertificate, certificatePrivateKey)
    }
}

private inline fun <reified T> load(defaultImpl: Class<out T>): T {
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

private fun createExecutorService(maxCount: Int): ExecutorService {
    val threadCount = AtomicInteger(1)
    return Executors.newFixedThreadPool(maxCount) { runnable: Runnable? ->
        Thread(runnable).apply {
            isDaemon = true
            name = "th2-http-client-${threadCount.incrementAndGet()}"
        }
    }
}