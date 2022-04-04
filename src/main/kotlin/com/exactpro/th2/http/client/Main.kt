/*
 * Copyright 2020-2021 Exactpro (Exactpro Systems Limited)
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
import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.message.MessageListener
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.common.schema.message.QueueAttribute.FIRST
import com.exactpro.th2.common.schema.message.QueueAttribute.SECOND
import com.exactpro.th2.common.schema.message.storeEvent
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
import com.exactpro.th2.http.client.util.toBatch
import com.exactpro.th2.http.client.util.toPrettyString
import com.exactpro.th2.http.client.util.toRawMessage
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.prometheus.client.Counter
import mu.KotlinLogging
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.EnumMap
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentLinkedDeque
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

    val counters: Map<Direction, Counter.Child> = EnumMap<Direction, Counter.Child>(Direction::class.java).apply {
        put(Direction.FIRST, Counter.build().apply {
            name("th2_conn_incoming_msg_quantity")
            labelNames("session_alias")
            help("Quantity of incoming messages to conn")
        }.register().labels(settings.sessionAlias))
        put(Direction.SECOND, Counter.build().apply {
            name("th2_conn_outgoing_msg_quantity")
            labelNames("session_alias")
            help("Quantity of outgoing messages from conn")
        }.register().labels(settings.sessionAlias))
    }

    val onRequest: (RawHttpRequest) -> Unit = { request: RawHttpRequest ->
        val rawMessage = request.toRawMessage(connectionId, outgoingSequence())

        messageRouter.send(rawMessage.toBatch(), SECOND.toString())
        counters[Direction.SECOND]!!.inc()
        eventRouter.storeEvent(
            "Sent HTTP request",
            if (rawMessage.hasParentEventId()) rawMessage.parentEventId.id else rootEventId,
            rawMessage.metadata.id
        )
    }

    val onResponse = { request: RawHttpRequest, response: RawHttpResponse<*> ->
        messageRouter.send(response.toRawMessage(connectionId, incomingSequence(), request).toBatch(), FIRST.toString())
        counters[Direction.FIRST]!!.inc()
        stateManager.onResponse(response)
    }

    val client = HttpClient(
        settings.https,
        settings.host,
        settings.port,
        settings.readTimeout,
        settings.keepAliveTimeout,
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
        eventRouter.storeEvent(rootEventId, "Failed to init state manager", "Error", it)
        throw it
    }

    requestHandler.runCatching {
        registerResource("request-handler", ::close)
        init(RequestHandlerContext(client))
    }.onFailure {
        LOGGER.error(it) { "Failed to init request handler" }
        eventRouter.storeEvent(rootEventId, "Failed to init request handler", "Error", it)
        throw it
    }

    val listener = MessageListener<MessageGroupBatch> { _, message ->
        message.groupsList.forEach { group ->
            group.runCatching(requestHandler::onRequest).recoverCatching {
                LOGGER.error(it) { "Failed to handle message group: ${group.toPrettyString()}" }
                eventRouter.storeEvent(rootEventId, "Failed to handle message group: ${group.toPrettyString()}", "Error", it)
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
    val keepAliveTimeout: Long = 15000,
    val defaultHeaders: Map<String, List<String>> = emptyMap(),
    val sessionAlias: String,
    val auth: IAuthSettings? = null,
    val validateCertificates: Boolean = true,
    @JsonDeserialize(converter = CertificateConverter::class) val clientCertificate: X509Certificate? = null,
    @JsonDeserialize(converter = PrivateKeyConverter::class) val certificatePrivateKey: PrivateKey? = null,
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

private fun MessageRouter<EventBatch>.storeEvent(name: String, eventId: String, messageId: MessageID) : String {
    val type = "Info"
    val status = Event.Status.PASSED
    val event = Event.start().apply {
        endTimestamp()
        name(name)
        type(type)
        status(status)

        messageID(messageId)
    }

    storeEvent(event, eventId)

    return event.id
}