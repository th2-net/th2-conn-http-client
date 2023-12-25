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

import com.exactpro.th2.common.grpc.Event
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.EventStatus
import com.exactpro.th2.common.schema.box.configuration.BoxConfiguration.DEFAULT_BOOK_NAME
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Direction
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.EventId
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.GroupBatch
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Message
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.TransportGroupBatchRouter.Companion.TRANSPORT_GROUP_ATTRIBUTE
import com.exactpro.th2.common.utils.event.transport.toProto
import com.exactpro.th2.common.utils.message.transport.toGroup
import com.exactpro.th2.http.client.annotations.IntegrationTest
import com.exactpro.th2.test.annotations.Th2AppFactory
import com.exactpro.th2.test.annotations.Th2IntegrationTest
import com.exactpro.th2.test.annotations.Th2TestFactory
import com.exactpro.th2.test.queue.CollectorMessageListener
import com.exactpro.th2.test.spec.CustomConfigSpec
import com.exactpro.th2.test.spec.RabbitMqSpec
import com.exactpro.th2.test.spec.RabbitMqSpec.Companion.EVENTS_PIN_NAME
import com.exactpro.th2.test.spec.filter
import com.exactpro.th2.test.spec.message
import com.exactpro.th2.test.spec.pin
import com.exactpro.th2.test.spec.pins
import com.exactpro.th2.test.spec.publishers
import com.exactpro.th2.test.spec.subscribers
import mu.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import strikt.api.Assertion
import strikt.api.expectThat
import strikt.assertions.all
import strikt.assertions.hasSize
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.matches
import strikt.assertions.withElementAt
import java.time.Duration.ofSeconds
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.test.assertNotNull

@IntegrationTest
@Th2IntegrationTest
class ApplicationIntegrationTest {
    @JvmField
    @Suppress("unused")
    internal val customConfig = CustomConfigSpec.fromString(
        """
        {
            "host": "127.0.0.1",
            "port": 8080,
            "sessionAlias": "some_api",
            "validateCertificates": false,
            "sessionAlias": "test-session-alias",
            "useTransport": true
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
                    filter {
                        message {
                            field("test") shouldBeEqualTo "a"
                        }
                    }
                }
            }

            publishers {
                pin("pub") {
                    attributes(TRANSPORT_GROUP_ATTRIBUTE)
                }
            }
        }

    private val resources = ConcurrentLinkedDeque<Pair<String, () -> Unit>>()

    @AfterEach
    fun afterEach() {
        resources.descendingIterator().forEach { (resource, destructor) ->
            LOGGER.info { "Destroying resource: $resource" }
            runCatching(destructor).apply {
                onSuccess { LOGGER.info { "Successfully destroyed resource: $resource" } }
                onFailure { LOGGER.error(it) { "Failed to destroy resource: $resource" } }
            }
        }
    }

    @Test
    fun `failed connection when process message without parent event id test`(
        @Th2AppFactory appFactory: CommonFactory,
        @Th2TestFactory testFactory: CommonFactory,
    ) {
        val eventListener = CollectorMessageListener.createWithCapacity<EventBatch>(1)
        testFactory.eventBatchRouter.subscribe(eventListener, EVENTS_PIN_NAME)

        val application = Application(appFactory) { resource, destructor ->
            resources += resource to destructor
        }

        val rootEventId: EventID = eventListener.assertRootEvent().id

        application.start()

        testFactory.sendMessages(RawMessage.builder().apply {
            idBuilder().apply {
                setSessionAlias("test-session-alias")
                setTimestamp(Instant.now())
                setDirection(Direction.OUTGOING)
                setSequence(1)
            }
        }.build())

        assertNotNull(eventListener.poll(ofSeconds(2))).also {
            expectThat(it) {
                get { eventsList }.apply {
                    hasSize(1)
                    withElementAt(0) {
                        get { name }.isEqualTo("Failed to handle transport message group")
                        get { type }.isEqualTo("Error")
                        get { status }.isEqualTo(EventStatus.FAILED)
                        get { id }.apply {
                            get { bookName }.isEqualTo(rootEventId.bookName)
                            get { scope }.isEqualTo(rootEventId.scope)
                        }
                        get { parentId }.isEqualTo(rootEventId)
                        get { attachedMessageIdsList }.isEmpty()
                        get { body.toString(Charsets.UTF_8) }.isEqualTo(
                            """
                            [{"data":"java.net.ConnectException: Connection refused (Connection refused)","type":"message"}]
                        """.trimIndent()
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `failed connection when process message with parent event id test`(
        @Th2AppFactory appFactory: CommonFactory,
        @Th2TestFactory testFactory: CommonFactory,
    ) {
        val eventListener = CollectorMessageListener.createWithCapacity<EventBatch>(1)
        testFactory.eventBatchRouter.subscribe(eventListener, EVENTS_PIN_NAME)

        val application = Application(appFactory) { resource, destructor ->
            resources += resource to destructor
        }

        eventListener.assertRootEvent()

        application.start()

        val eventId = EventId.builder().apply {
            setBook(BOOK_TEST)
            setScope(SCOPE_TEST_A)
            setTimestamp(Instant.now())
            setId("test-id")
        }.build()

        testFactory.sendMessages(RawMessage.builder().apply {
            idBuilder().apply {
                setSessionAlias("test-session-alias")
                setTimestamp(Instant.now())
                setDirection(Direction.OUTGOING)
                setSequence(1)
            }
            setEventId(eventId)
        }.build())

        assertNotNull(eventListener.poll(ofSeconds(2))).also {
            expectThat(it) {
                get { eventsList }.apply {
                    hasSize(1)
                    withElementAt(0) {
                        get { name }.isEqualTo("Failed to handle transport message group")
                        get { type }.isEqualTo("Error")
                        get { status }.isEqualTo(EventStatus.FAILED)
                        get { id }.apply {
                            get { bookName }.isEqualTo(eventId.book)
                            get { scope }.isEqualTo(eventId.scope)
                        }
                        get { parentId }.isEqualTo(eventId.toProto())
                        get { attachedMessageIdsList }.isEmpty()
                        get { body.toString(Charsets.UTF_8) }.isEqualTo(
                            """
                            [{"data":"java.net.ConnectException: Connection refused (Connection refused)","type":"message"}]
                        """.trimIndent()
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `failed connection when process messages with parent event id test`(
        @Th2AppFactory appFactory: CommonFactory,
        @Th2TestFactory testFactory: CommonFactory,
    ) {
        val eventListener = CollectorMessageListener.createWithCapacity<EventBatch>(1)
        testFactory.eventBatchRouter.subscribe(eventListener, EVENTS_PIN_NAME)

        val application = Application(appFactory) { resource, destructor ->
            resources += resource to destructor
        }

        eventListener.assertRootEvent()

        application.start()

        val eventIdA = EventId.builder().apply {
            setBook(BOOK_TEST)
            setScope(SCOPE_TEST_A)
            setTimestamp(Instant.now())
            setId("test-id")
        }.build()

        val eventIdB = EventId.builder().apply {
            setBook(BOOK_TEST)
            setScope(SCOPE_TEST_B)
            setTimestamp(Instant.now())
            setId("test-id")
        }.build()

        testFactory.sendMessages(
            RawMessage.builder().apply {
                idBuilder().apply {
                    setSessionAlias("test-session-alias")
                    setTimestamp(Instant.now())
                    setDirection(Direction.OUTGOING)
                    setSequence(1)
                }
                setEventId(eventIdA)
            }.build(),
            RawMessage.builder().apply {
                idBuilder().apply {
                    setSessionAlias("test-session-alias")
                    setTimestamp(Instant.now())
                    setDirection(Direction.OUTGOING)
                    setSequence(1)
                }
                setEventId(eventIdB)
            }.build(),
        )

        val events = listOf(
            assertNotNull(eventListener.poll(ofSeconds(2))),
            assertNotNull(eventListener.poll(ofSeconds(2))),
        )

        expectThat(events) {
            all {
                get { eventsList }.apply {
                    hasSize(1)
                    withElementAt(0) {
                        get { name }.isEqualTo("Failed to handle transport message group")
                        get { type }.isEqualTo("Error")
                        get { status }.isEqualTo(EventStatus.FAILED)
                        get { id }.apply {
                            get { bookName }.isEqualTo(BOOK_TEST)
                        }
                        get { attachedMessageIdsList }.isEmpty()
                        get { body.toString(Charsets.UTF_8) }.isEqualTo(
                            """
                        [{"data":"java.net.ConnectException: Connection refused (Connection refused)","type":"message"}]
                    """.trimIndent()
                        )
                    }
                }
            }
            withElementAt(0) {
                get { getEvents(0) }.apply {
                    get { id }.apply {
                        get { scope }.isEqualTo(eventIdA.scope)
                    }
                    get { parentId }.isEqualTo(eventIdA.toProto())
                }
            }
            withElementAt(1) {
                get { getEvents(0) }.apply {
                    get { id }.apply {
                        get { scope }.isEqualTo(eventIdB.scope)
                    }
                    get { parentId }.isEqualTo(eventIdB.toProto())
                }
            }
        }
    }

    private fun CommonFactory.sendMessages(
        vararg messages: Message<*>
    ) {
        transportGroupBatchRouter.send(
            GroupBatch.builder().apply {
                setBook(BOOK_TEST)
                setSessionGroup(SESSION_GROUP_TEST)

                messages.asSequence()
                    .map(Message<*>::toGroup)
                    .forEach(this::addGroup)
            }.build(),
            "sub"
        )
    }

    private fun CollectorMessageListener<EventBatch>.assertRootEvent() =
        assertNotNull(poll(ofSeconds(1))).also {
            expectThat(it) {
                get { eventsList }.apply {
                    hasSize(1)
                    withElementAt(0) {
                        isRootEvent(DEFAULT_BOOK_NAME, "app")
                    }
                }
            }
        }.getEvents(0)

    companion object {
        private val LOGGER = KotlinLogging.logger { }

        private const val BOOK_TEST = "test-book-A"
        private const val SCOPE_TEST_A = "test-scope-A"
        private const val SCOPE_TEST_B = "test-scope-B"
        private const val SESSION_GROUP_TEST = "test-session-group"

        fun Assertion.Builder<Event>.isRootEvent(book: String, scope: String) {
            get { id }.apply {
                get { getBookName() }.isEqualTo(book)
                get { getScope() }.isEqualTo(scope)
            }
            get { hasParentId() }.isFalse()
            get { name }.matches(Regex("$scope \\d{4}-[01]\\d-[0-3]\\dT[0-2]\\d:[0-5]\\d:[0-5]\\d\\.\\d+([+-][0-2]\\d:[0-5]\\d|Z) - Root event"))
            get { type }.isEqualTo("Microservice")
            get { status }.isEqualTo(EventStatus.SUCCESS)
        }
    }
}