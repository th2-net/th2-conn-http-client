/*
 * Copyright 2024-2026 Exactpro (Exactpro Systems Limited)
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
import com.exactpro.th2.common.grpc.EventStatus
import com.exactpro.th2.common.schema.box.configuration.BoxConfiguration.DEFAULT_BOOK_NAME
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Direction
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Direction.INCOMING
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Direction.OUTGOING
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.EventId
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.GroupBatch
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.TransportGroupBatchRouter.Companion.TRANSPORT_GROUP_ATTRIBUTE
import com.exactpro.th2.common.utils.event.transport.toProto
import com.exactpro.th2.common.utils.message.transport.toGroup
import com.exactpro.th2.common.utils.message.transport.toProto
import com.exactpro.th2.http.client.annotations.IntegrationTest
import com.exactpro.th2.test.annotations.Th2AppFactory
import com.exactpro.th2.test.annotations.Th2IntegrationTest
import com.exactpro.th2.test.annotations.Th2TestFactory
import com.exactpro.th2.test.extension.CleanupExtension
import com.exactpro.th2.test.queue.CollectorMessageListener
import com.exactpro.th2.test.spec.CustomConfigSpec
import com.exactpro.th2.test.spec.RabbitMqSpec
import com.exactpro.th2.test.spec.RabbitMqSpec.Companion.EVENTS_PIN_NAME
import com.exactpro.th2.test.spec.pin
import com.exactpro.th2.test.spec.pins
import com.exactpro.th2.test.spec.publishers
import com.exactpro.th2.test.spec.subscribers
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import rawhttp.core.RawHttp
import rawhttp.core.server.TcpRawHttpServer
import strikt.api.expectThat
import strikt.assertions.all
import strikt.assertions.endsWith
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isGreaterThan
import strikt.assertions.isNotBlank
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import strikt.assertions.single
import java.time.Duration.ofSeconds
import java.time.Instant
import java.util.EnumMap
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@IntegrationTest
@Th2IntegrationTest
class ValidApplicationIntegrationTest {
    @JvmField
    @Suppress("unused")
    internal val customConfig = CustomConfigSpec.fromString(
        """
        {
            "useTransport": true,
            "sessions": {
                "$SESSION_ALIAS_1_TEST": {
                    "host": "$SERVER_HOST",
                    "port": $SERVER_PORT,
                    "validateCertificates": false,
                    "maxParallelRequests": $MAX_PARALLEL_REQUESTS
                },
                "$SESSION_ALIAS_2_TEST": {
                    "host": "$SERVER_HOST",
                    "port": $SERVER_PORT,
                    "validateCertificates": false,
                    "maxParallelRequests": $MAX_PARALLEL_REQUESTS
                }
            }
        }
        """.trimIndent()
    )

    @JvmField
    @Suppress("unused")
    internal val mq = RabbitMqSpec.create()
        .pins {
            subscribers {
                pin("sub") {
                    attributes(INPUT_QUEUE_TRANSPORT_ATTRIBUTE, TRANSPORT_GROUP_ATTRIBUTE)
                }
            }
            publishers {
                pin("pub") {
                    attributes(TRANSPORT_GROUP_ATTRIBUTE)
                }
            }
        }

    @Timeout(30)
    @ParameterizedTest
    @CsvSource(
        "$SESSION_ALIAS_1_TEST,$SESSION_GROUP_1_TEST",
        "$SESSION_ALIAS_2_TEST,$SESSION_GROUP_2_TEST",
    )
    fun `sequence order test`(
        sessionAlias: String,
        sessionGroup: String,
        @Th2TestFactory testFactory: CommonFactory,
        @Th2AppFactory appFactory: CommonFactory,
        resources: CleanupExtension.Registry,
    ) {
        val iterations = 1_000
        val messageListener = CollectorMessageListener.createWithCapacity<GroupBatch>(iterations)
        testFactory.transportGroupBatchRouter.subscribe(messageListener, "pub")

        val application = Application(appFactory) { resource, destructor ->
            resources.add(resource, destructor)
        }

        application.start()

        val messageGroup = RawMessage.builder().apply {
            idBuilder()
                .setSessionAlias(sessionAlias)
                .setTimestamp(Instant.now())
                .setDirection(OUTGOING)
                .setSequence(1)
        }.build().toGroup()

        val groupBatch = GroupBatch.builder().apply {
            setBook(BOOK_TEST)
            setSessionGroup(sessionGroup)
            groupsBuilder().apply {
                repeat(iterations) {
                    add(messageGroup)
                }
            }
        }.build()

        testFactory.transportGroupBatchRouter.send(groupBatch, "sub")

        val messageCounter = EnumMap<Direction, Int>(Direction::class.java)
        val sequences = EnumMap<Direction, Long>(Direction::class.java)
        while (messageCounter[INCOMING] != iterations && messageCounter[OUTGOING] != iterations) {
            val batch = assertNotNull(
                messageListener.poll(ofSeconds(2)),
                "Batch not null, messages: $messageCounter"
            )

            batch.groups.forEach { group ->
                group.messages.forEach { message ->
                    with(message.id) {
                        messageCounter.merge(direction, 1, Int::plus)
                        val previous = sequences.put(direction, sequence) ?: 0
                        assertTrue(
                            previous < sequence,
                            """
                             Decrease sequence
                                direction: $direction
                                previous: $previous
                                current: $sequence
                                messages: $messageCounter
                            """.trimIndent()
                        )
                    }
                }
            }
        }
    }

    @Timeout(30)
    @ParameterizedTest
    @CsvSource(
        "$SESSION_ALIAS_1_TEST,$SESSION_GROUP_1_TEST",
        "$SESSION_ALIAS_2_TEST,$SESSION_GROUP_2_TEST",
    )
    fun `send message with parent event id test`(
        sessionAlias: String,
        sessionGroup: String,
        @Th2TestFactory testFactory: CommonFactory,
        @Th2AppFactory appFactory: CommonFactory,
        resources: CleanupExtension.Registry,
    ) {
        val messageListener = CollectorMessageListener.createWithCapacity<GroupBatch>(10)
        testFactory.transportGroupBatchRouter.subscribe(messageListener, "pub")
        val eventListener = CollectorMessageListener.createWithCapacity<EventBatch>(10)
        testFactory.eventBatchRouter.subscribe(eventListener, EVENTS_PIN_NAME)

        val application = Application(appFactory) { resource, destructor ->
            resources.add(resource, destructor)
        }

        application.start()

        val now = Instant.now()
        val eventId = EventId.builder()
            .setBook(BOOK_TEST)
            .setScope(SCOPE_TEST)
            .setTimestamp(now)
            .setId("test-id")
            .build()

        testFactory.transportGroupBatchRouter.send(GroupBatch.builder().apply {
            setBook(BOOK_TEST)
            setSessionGroup(sessionGroup)
            groupsBuilder().add(RawMessage.builder().apply {
                idBuilder()
                    .setSessionAlias(sessionAlias)
                    .setTimestamp(now)
                    .setDirection(OUTGOING)
                    .setSequence(1)
                setEventId(eventId)
            }.build().toGroup())
        }.build(), "sub")

        val group = assertNotNull(messageListener.poll(ofSeconds(2)), "Message Group")
        expectThat(group) and {
            get { this.book } isEqualTo DEFAULT_BOOK_NAME
            get { this.sessionGroup } isEqualTo sessionAlias
            get { this.groups }.hasSize(2) and {
                all {
                    get { messages }.single() and {
                        get { this.id } and {
                            get { this.sessionAlias } isEqualTo sessionAlias
                            get { this.sequence } isGreaterThan 0
                            get { this.timestamp } isGreaterThan now
                            get { this.subsequence }.isEmpty()
                        }
                        get { this.eventId }.isNotNull() and {
                            get { this.id } isEqualTo "test-id"
                            get { this.book } isEqualTo BOOK_TEST
                            get { this.scope } isEqualTo SCOPE_TEST
                            get { this.timestamp } isEqualTo now
                        }
                        get { this.metadata }.hasSize(3) and {
                            get {this["method"] } isEqualTo "GET"
                            get {this["uri"] } isEqualTo "http://$SERVER_HOST:$SERVER_PORT/"
                            get {this["th2-request-id"] }.isNotNull().isNotBlank()
                        }
                        get { this.protocol }.isEmpty()
                        isA<RawMessage>() and {
                            get { this.body.toString(Charsets.UTF_8) }.isNotBlank()
                        }
                    }
                }
                get { this[0] } and {
                    get { this.messages }.single() and {
                        get { this.id } and {
                            get { this.direction } isEqualTo OUTGOING
                        }
                    }
                }
                get { this[1] } and {
                    get { this.messages }.single() and {
                        get { this.id } and {
                            get { this.direction } isEqualTo INCOMING
                        }
                        isA<RawMessage>() and {
                            get { this.body.toString(Charsets.UTF_8) } endsWith BODY
                        }
                    }
                }
            }
        }
        assertNull(messageListener.poll(ofSeconds(1)), "Empty second batch")

        val rootEventId: EventID = eventListener.assertRootEvent().id
        eventListener.assertClientEvent(rootEventId, SESSION_ALIAS_1_TEST)
        eventListener.assertClientEvent(rootEventId, SESSION_ALIAS_2_TEST)
        expectThat(eventListener.poll(ofSeconds(2))).isNotNull() and {
            get { this.eventsList }.single().and {
                get { this.name }.isEqualTo("Sent HTTP request")
                get { this.type }.isEqualTo("Send message")
                get { this.status }.isEqualTo(EventStatus.SUCCESS)
                get { this.id }.and {
                    get { this.bookName }.isEqualTo(eventId.book)
                    get { this.scope }.isEqualTo(eventId.scope)
                }
                get { this.parentId }.isEqualTo(eventId.toProto())
                get { this.attachedMessageIdsList }.single() isEqualTo group.groups.first().messages.first().id.toProto(group)
                get { body.toString(Charsets.UTF_8) }.isEqualTo("[]")
            }
        }
        assertNull(eventListener.poll(ofSeconds(1)), "Empty event")
    }

    @Timeout(30)
    @ParameterizedTest
    @CsvSource(
        "$SESSION_ALIAS_1_TEST,$SESSION_GROUP_1_TEST",
        "$SESSION_ALIAS_2_TEST,$SESSION_GROUP_2_TEST",
    )
    fun `send message without parent event id test`(
        sessionAlias: String,
        sessionGroup: String,
        @Th2TestFactory testFactory: CommonFactory,
        @Th2AppFactory appFactory: CommonFactory,
        resources: CleanupExtension.Registry,
    ) {
        val messageListener = CollectorMessageListener.createWithCapacity<GroupBatch>(10)
        testFactory.transportGroupBatchRouter.subscribe(messageListener, "pub")
        val eventListener = CollectorMessageListener.createWithCapacity<EventBatch>(10)
        testFactory.eventBatchRouter.subscribe(eventListener, EVENTS_PIN_NAME)

        val application = Application(appFactory) { resource, destructor ->
            resources.add(resource, destructor)
        }

        application.start()

        val now = Instant.now()
        testFactory.transportGroupBatchRouter.send(GroupBatch.builder().apply {
            setBook(BOOK_TEST)
            setSessionGroup(sessionGroup)
            groupsBuilder().add(RawMessage.builder().apply {
                idBuilder()
                    .setSessionAlias(sessionAlias)
                    .setTimestamp(now)
                    .setDirection(OUTGOING)
                    .setSequence(1)
            }.build().toGroup())
        }.build(), "sub")

        val group = assertNotNull(messageListener.poll(ofSeconds(2)), "Message Group")
        expectThat(group) and {
            get { this.book } isEqualTo DEFAULT_BOOK_NAME
            get { this.sessionGroup } isEqualTo sessionAlias
            get { this.groups }.hasSize(2) and {
                all {
                    get { messages }.single() and {
                        get { this.id } and {
                            get { this.sessionAlias } isEqualTo sessionAlias
                            get { this.sequence } isGreaterThan 0
                            get { this.timestamp } isGreaterThan now
                            get { this.subsequence }.isEmpty()
                        }
                        get { this.eventId }.isNull()
                        get { this.metadata }.hasSize(3) and {
                            get {this["method"] } isEqualTo "GET"
                            get {this["uri"] } isEqualTo "http://$SERVER_HOST:$SERVER_PORT/"
                            get {this["th2-request-id"] }.isNotNull().isNotBlank()
                        }
                        get { this.protocol }.isEmpty()
                        isA<RawMessage>() and {
                            get { this.body.toString(Charsets.UTF_8) }.isNotBlank()
                        }
                    }
                }
                get { this[0] } and {
                    get { this.messages }.single() and {
                        get { this.id } and {
                            get { this.direction } isEqualTo OUTGOING
                        }
                    }
                }
                get { this[1] } and {
                    get { this.messages }.single() and {
                        get { this.id } and {
                            get { this.direction } isEqualTo INCOMING
                        }
                        isA<RawMessage>() and {
                            get { this.body.toString(Charsets.UTF_8) } endsWith BODY
                        }
                    }
                }
            }
        }
        assertNull(messageListener.poll(ofSeconds(1)), "Empty second batch")

        val rootEventId: EventID = eventListener.assertRootEvent().id
        val aliasToEventId = listOf(SESSION_ALIAS_1_TEST, SESSION_ALIAS_2_TEST)
            .associateWith { eventListener.assertClientEvent(rootEventId, it).id }

        val eventId = assertNotNull(aliasToEventId[sessionAlias], "Client event id")
        expectThat(eventListener.poll(ofSeconds(2))).isNotNull() and {
            get { this.eventsList }.single().and {
                get { this.name }.isEqualTo("Sent HTTP request")
                get { this.type }.isEqualTo("Send message")
                get { this.status }.isEqualTo(EventStatus.SUCCESS)
                get { this.id }.and {
                    get { this.bookName }.isEqualTo(eventId.bookName)
                    get { this.scope }.isEqualTo(eventId.scope)
                }
                get { this.parentId }.isEqualTo(eventId)
                get { this.attachedMessageIdsList }.single() isEqualTo group.groups.first().messages.first().id.toProto(group)
                get { body.toString(Charsets.UTF_8) }.isEqualTo("[]")
            }
        }
        assertNull(eventListener.poll(ofSeconds(1)), "Empty event")
    }

    companion object {
        private val LOGGER = KotlinLogging.logger { }

        private const val BOOK_TEST = DEFAULT_BOOK_NAME
        private const val SCOPE_TEST = "test-scope"
        private const val SESSION_ALIAS_1_TEST = "test-session-alias-1"
        private const val SESSION_GROUP_1_TEST = "test-session-group-1"
        private const val SESSION_ALIAS_2_TEST = "test-session-alias-2"
        private const val SESSION_GROUP_2_TEST = "test-session-group-2"

        private const val MAX_PARALLEL_REQUESTS = 5
        private const val SERVER_HOST = "127.0.0.1"
        private const val SERVER_PORT = 8086
        private const val BODY =
            """{ "id" : 901, "name" : { "first":"Tom", "middle":"and", "last":"Jerry" }, "phones" : [ {"type" : "home", "number" : "1233333" }, {"type" : "work", "number" : "264444" }], "lazy" : false, "married" : null }"""
        private val RESPONSE_DATA = """
                  HTTP/1.1 200 OK
                  Content-Type: plain/text
                  Content-Length: ${BODY.length}
                  
                  $BODY
                  """.trimIndent()

        private val SERVER_RESPONSE_COUNTER = AtomicInteger(0)

        private val SERVER = TcpRawHttpServer(SERVER_PORT)

        @BeforeAll
        @JvmStatic
        fun setUp() {
            SERVER.start {
                LOGGER.debug { "Received request: ${it.eagerly().startLine}" }
                SERVER_RESPONSE_COUNTER.incrementAndGet()
                Optional.of(RawHttp().parseResponse(RESPONSE_DATA))
            }
        }

        @AfterAll
        @JvmStatic
        fun finish() {
            SERVER.stop()
        }
    }
}