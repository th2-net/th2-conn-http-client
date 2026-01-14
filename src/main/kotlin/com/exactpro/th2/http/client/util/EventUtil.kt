/*
 * Copyright 2026 Exactpro (Exactpro Systems Limited)
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

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.grpc.Direction.SECOND
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.common.message.direction
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.Direction.OUTGOING
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.GroupBatch
import com.exactpro.th2.common.utils.event.EventBatcher
import com.exactpro.th2.common.utils.event.transport.toProto
import com.exactpro.th2.common.utils.message.transport.toProto

fun EventBatcher.publishSentEvents(rootEventId: EventID, batch: MessageGroupBatch) {
    publishSentEvent(hashMapOf<EventID, MutableList<MessageID>>().apply {
        for (group in batch.groupsList) {
            val message = group.messagesList[0].rawMessage
            if (message.direction != SECOND) continue
            val eventId = message.eventId ?: rootEventId
            this@apply.computeIfAbsent(eventId) { mutableListOf() } += message.metadata.id
        }
    })
}

fun EventBatcher.publishSentEvents(rootEventId: EventID, batch: GroupBatch) {
    publishSentEvent(hashMapOf<EventID, MutableList<MessageID>>().apply {
        for (group in batch.groups) {
            val message = group.messages[0]
            if (message.id.direction != OUTGOING) continue
            val eventId = message.eventId?.toProto() ?: rootEventId
            this@apply.computeIfAbsent(eventId) { mutableListOf() } += message.id.toProto(batch.book, batch.sessionGroup)
        }
    })
}

fun EventBatcher.publishSentEvent(eventMessages: Map<EventID, MutableList<MessageID>>) {
    for ((eventId, messageIds) in eventMessages) {
        storeEvent(
            eventId,
            "Sent HTTP request",
            "Send message",
            messageIds = messageIds
        )
    }
}

fun EventBatcher.storeEvent(
    parentId: EventID,
    name: String,
    type: String,
    cause: Throwable? = null,
    messageIds: Iterable<MessageID> = emptySet(),
): Event = Event.start().apply {
    endTimestamp()
    name(name)
    type(type)
    messageIds.forEach(this::messageID)
    status(if (cause != null) Event.Status.FAILED else Event.Status.PASSED)

    var error = cause

    while (error != null) {
        exception(error, true)
        error = error.cause
    }

    onEvent(toProto(parentId))
}