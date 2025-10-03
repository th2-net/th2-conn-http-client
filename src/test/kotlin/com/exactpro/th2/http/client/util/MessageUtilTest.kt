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
package com.exactpro.th2.http.client.util

import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Direction
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Message
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageId
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.ParsedMessage
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.RawMessage
import org.junit.jupiter.api.Test
import java.time.Instant

private const val TEST_SESSION_ALIAS = "test-session-alias"

class MessageUtilTest {

    @Test
    fun castTest() {
        ParsedMessage.builder()
            .setId(messageId())
            .setType(REQUEST_MESSAGE)
            .setBody(mapOf("FieldA" to "ValueB"))
            .build()
            .cast<Message<*>>("abstract")
            .cast<ParsedMessage>("parsed")

        RawMessage.builder()
            .setId(messageId())
            .setBody(byteArrayOf(1, 2, 3))
            .build()
            .cast<Message<*>>("abstract")
            .cast<RawMessage>("raw")
    }

    private fun messageId() = MessageId.builder()
        .setSessionAlias(TEST_SESSION_ALIAS)
        .setDirection(Direction.OUTGOING)
        .setTimestamp(Instant.now())
        .setSequence(1)
        .build()
}