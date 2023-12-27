/*
 * Copyright 2023 Exactpro (Exactpro Systems Limited)
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

import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.message.MessageListener
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.GroupBatch
import com.exactpro.th2.common.utils.event.EventBatcher
import com.exactpro.th2.common.utils.event.storeEvent
import com.exactpro.th2.common.utils.event.transport.toProto
import com.exactpro.th2.common.utils.message.RAW_GROUP_SELECTOR
import com.exactpro.th2.common.utils.message.RawMessageBatcher
import com.exactpro.th2.common.utils.message.parentEventIds
import com.exactpro.th2.common.utils.message.transport.MessageBatcher
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
import com.google.common.util.concurrent.ThreadFactoryBuilder
import mu.KotlinLogging
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.ServiceLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong

private const val SEND_PIN_ATTRIBUTE = "send"
internal const val INPUT_QUEUE_TRANSPORT_ATTRIBUTE = SEND_PIN_ATTRIBUTE
private val INPUT_QUEUE_PROTO_ATTRIBUTES = arrayOf(SEND_PIN_ATTRIBUTE, "group")

class Application(
    factory: CommonFactory,
    private val registerResource: (name: String, destructor: () -> Unit) -> Unit,
) {
    private val stateManager = load<IStateManager>(BasicStateManager::class.java)
    private val requestHandler = load<IRequestHandler>(BasicRequestHandler::class.java)
    private val authSettingsType = load<IAuthSettingsTypeProvider>(BasicAuthSettingsTypeProvider::class.java).type

    private val settings: Settings
    private val eventRouter: MessageRouter<EventBatch> = factory.eventBatchRouter
    private val protoMR: MessageRouter<MessageGroupBatch> = factory.messageRouterMessageGroupBatch
    private val transportMR: MessageRouter<GroupBatch> = factory.transportGroupBatchRouter
    private val rootEventId: EventID = factory.rootEventId

    init {
        val mapper = JsonMapper.builder()
            .addModule(
                KotlinModule.Builder()
                    .withReflectionCacheSize(512)
                    .configure(KotlinFeature.NullToEmptyCollection, false)
                    .configure(KotlinFeature.NullToEmptyMap, false)
                    .configure(KotlinFeature.NullIsSameAsDefault, true)
                    .configure(KotlinFeature.SingletonSupport, true)
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

        settings = factory.getCustomConfiguration(Settings::class.java, mapper)
    }

    fun start() {
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
                val messageBatcher =
                    MessageBatcher(maxBatchSize, maxFlushTime, book, GROUP_SELECTOR, executor, onError, transportMR::send)
                        .also { registerResource("transport message batcher", it::close) }

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
                val connectionId = com.exactpro.th2.common.grpc.ConnectionID.newBuilder()
                    .setSessionAlias(sessionAlias)
                    .setSessionGroup(sessionGroup)
                    .build()

                val messageBatcher = RawMessageBatcher(maxBatchSize, maxFlushTime, RAW_GROUP_SELECTOR, executor, onError) {
                    protoMR.send(it, com.exactpro.th2.common.schema.message.QueueAttribute.RAW.value)
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
                                group.eventIds.map(com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.EventId::toProto).ifEmpty { sequenceOf(rootEventId) }.forEach {
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
        }
    }

    companion object {
        private val LOGGER = KotlinLogging.logger { }
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

private fun createExecutorService(maxCount: Int): ExecutorService =
    Executors.newFixedThreadPool(maxCount, ThreadFactoryBuilder()
        .setDaemon(true)
        .setNameFormat("th2-http-client-%d")
        .build())