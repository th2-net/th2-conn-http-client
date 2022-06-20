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

@file:JvmName("Main")

package com.exactpro.th2.http.client

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.message.MessageListener
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.common.schema.message.QueueAttribute.FIRST
import com.exactpro.th2.common.schema.message.QueueAttribute.SECOND
import com.exactpro.th2.common.schema.message.storeEvent
import com.exactpro.th2.common.utils.event.EventBatcher
import com.exactpro.th2.common.utils.event.MessageBatcherDirection
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
import com.exactpro.th2.http.client.util.createExecutorService
import com.exactpro.th2.http.client.util.storeEvent
import com.exactpro.th2.http.client.util.toPrettyString
import com.exactpro.th2.http.client.util.toRawMessage
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.system.exitProcess

private val LOGGER = KotlinLogging.logger { }
private const val INPUT_QUEUE_ATTRIBUTE = "send"

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

    val factory = args.runCatching(CommonFactory::createFromArguments).getOrElse {
        LOGGER.error(it) { "Failed to create common factory with arguments: ${args.joinToString(" ")}" }
        CommonFactory()
    }.apply { resources += "factory" to ::close }

    val mapper = JsonMapper.builder()
        .addModule(KotlinModule(nullIsSameAsDefault = true))
        .addModule(SimpleModule().addDeserializer(IAuthSettings::class.java, AuthSettingsDeserializer(authSettingsType)))
        .build()

    val settings = factory.getCustomConfiguration(Settings::class.java, mapper)
    val eventRouter = factory.eventBatchRouter
    val messageRouter = factory.messageRouterMessageGroupBatch

    run(settings, eventRouter, messageRouter, stateManager, requestHandler) { resource, destructor ->
        resources += resource to destructor
    }
} catch (e: Exception) {
    LOGGER.error(e) { "Uncaught exception. Shutting down" }
    exitProcess(1)
}

fun run(
    settings: Settings,
    eventRouter: MessageRouter<EventBatch>,
    messageRouter: MessageRouter<MessageGroupBatch>,
    stateManager: IStateManager,
    requestHandler: IRequestHandler,
    registerResource: (name: String, destructor: () -> Unit) -> Unit
) {
    val connectionId = ConnectionID.newBuilder().setSessionAlias(settings.sessionAlias).build()

    val rootEventId = eventRouter.storeEvent(Event.start().apply {
        endTimestamp()
        name("HTTP client '${settings.sessionAlias}' ${Instant.now()}")
        type("Microservice")
    }).id

    val incomingSequence = createSequence()
    val outgoingSequence = createSequence()

    val scheduledExecutorService = Executors.newScheduledThreadPool(1).also {
        registerResource("Batcher scheduled executor", it::shutdownNow)
    }

    val messageBatcher = MessageBatcherDirection(settings.maxBatchSize, settings.maxFlushTime, scheduledExecutorService, onBatch = { batch, direction ->
        messageRouter.send(batch, when(direction) {
            Direction.FIRST -> FIRST
            Direction.SECOND -> SECOND
            else -> error("Unsupported direction $direction")
        }.toString())
    })

    val eventBatcher = EventBatcher(settings.maxBatchSize, settings.maxFlushTime, scheduledExecutorService) { batch ->
        eventRouter.send(batch)
    }

    val onRequest: (RawHttpRequest) -> Unit = { request: RawHttpRequest ->
        val rawMessage = request.toRawMessage(connectionId, outgoingSequence()).build()
        messageBatcher.onMessage(rawMessage, Direction.SECOND)
        eventBatcher.storeEvent("Sent HTTP request", rawMessage.metadata.id, if (rawMessage.hasParentEventId()) rawMessage.parentEventId.id else rootEventId)
    }

    val onResponse = { request: RawHttpRequest, response: RawHttpResponse<*> ->
        messageBatcher.onMessage(response.toRawMessage(connectionId, incomingSequence(), request).build(), Direction.FIRST)
        try {
            stateManager.onResponse(response)
        } catch (e: Exception) {
            LOGGER.error(e) {"Failed to execute onResponse method from state manager"}
        }
    }

    val client = HttpClient(
        settings.https,
        settings.host,
        settings.port,
        settings.readTimeout,
        settings.keepAliveTimeout,
        settings.maxParallelRequests,
        settings.defaultHeaders,
        stateManager::prepareRequest,
        onRequest,
        onResponse,
        stateManager::onStart,
        stateManager::onStop,
        settings.validateCertificates,
        settings.certificate
    ).apply { registerResource("client", ::close) }

    stateManager.runCatching {
        registerResource("state-manager", ::close)
        init(StateManagerContext(client, settings.auth))
    }.onFailure {
        LOGGER.error(it) { "Failed to init state manager" }
        eventBatcher.storeEvent("Failed to init state manager", "Error", rootEventId, it)
        throw it
    }

    requestHandler.runCatching {
        registerResource("request-handler", ::close)
        init(RequestHandlerContext(client))
    }.onFailure {
        LOGGER.error(it) { "Failed to init request handler" }
        eventBatcher.storeEvent("Failed to init request handler", "Error", rootEventId, it)
        throw it
    }

    val sendService: ExecutorService = createExecutorService(settings.maxParallelRequests)

    val listener = MessageListener<MessageGroupBatch> { _, message ->
        message.groupsList.forEach { group ->
            sendService.submit {
                group.runCatching(requestHandler::onRequest).recoverCatching {
                    LOGGER.error(it) { "Failed to handle message group: ${group.toPrettyString()}" }
                    eventBatcher.storeEvent("Failed to handle message group: ${group.toPrettyString()}", "Error", rootEventId, it)
                }
            }
        }
    }

    runCatching {
        checkNotNull(messageRouter.subscribe(listener, INPUT_QUEUE_ATTRIBUTE))
    }.onSuccess { monitor ->
        registerResource("raw-monitor", monitor::unsubscribe)
    }.onFailure {
        throw IllegalStateException("Failed to subscribe to input queue", it)
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
    @JsonDeserialize(converter = CertificateConverter::class) val clientCertificate: X509Certificate? = null,
    @JsonDeserialize(converter = PrivateKeyConverter::class) val certificatePrivateKey: PrivateKey? = null,
    val maxBatchSize: Int = 100,
    val maxFlushTime: Long = 1000
) {
    @JsonIgnore val certificate: Certificate? = clientCertificate?.run {
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