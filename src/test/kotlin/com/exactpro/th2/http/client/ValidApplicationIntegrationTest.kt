/*
 * Copyright 2024 Exactpro (Exactpro Systems Limited)
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

import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Direction
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Direction.INCOMING
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Direction.OUTGOING
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.GroupBatch
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.TransportGroupBatchRouter.Companion.TRANSPORT_GROUP_ATTRIBUTE
import com.exactpro.th2.common.utils.message.transport.toGroup
import com.exactpro.th2.http.client.annotations.IntegrationTest
import com.exactpro.th2.test.annotations.Th2AppFactory
import com.exactpro.th2.test.annotations.Th2IntegrationTest
import com.exactpro.th2.test.annotations.Th2TestFactory
import com.exactpro.th2.test.extension.CleanupExtension
import com.exactpro.th2.test.queue.CollectorMessageListener
import com.exactpro.th2.test.spec.CustomConfigSpec
import com.exactpro.th2.test.spec.RabbitMqSpec
import com.exactpro.th2.test.spec.pin
import com.exactpro.th2.test.spec.pins
import com.exactpro.th2.test.spec.publishers
import com.exactpro.th2.test.spec.subscribers
import mu.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import rawhttp.core.RawHttp
import rawhttp.core.server.TcpRawHttpServer
import java.time.Duration.ofSeconds
import java.time.Instant
import java.util.EnumMap
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@IntegrationTest
@Th2IntegrationTest
class ValidApplicationIntegrationTest {
    @JvmField
    @Suppress("unused")
    internal val customConfig = CustomConfigSpec.fromString(
        """
        {
            "host": "127.0.0.1",
            "port": $SERVER_PORT,
            "validateCertificates": false,
            "sessionAlias": "test-session-alias",
            "maxParallelRequests": $MAX_PARALLEL_REQUESTS,
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
                }
            }
            publishers {
                pin("pub") {
                    attributes(TRANSPORT_GROUP_ATTRIBUTE)
                }
            }
        }

    @Test
    @Timeout(30)
    fun `sequence order test`(
        @Th2AppFactory appFactory: CommonFactory,
        @Th2TestFactory testFactory: CommonFactory,
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
                .setSessionAlias(SESSION_ALIAS_TEST)
                .setTimestamp(Instant.now())
                .setDirection(OUTGOING)
                .setSequence(1)
        }.build().toGroup()

        val groupBatch = GroupBatch.builder().apply {
            setBook(BOOK_TEST)
            setSessionGroup(SESSION_GROUP_TEST)
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

    companion object {
        private val LOGGER = KotlinLogging.logger { }

        private const val BOOK_TEST = "test-book"
        private const val SESSION_ALIAS_TEST = "test-session-alias"
        private const val SESSION_GROUP_TEST = "test-session-group"

        private const val MAX_PARALLEL_REQUESTS = 5
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