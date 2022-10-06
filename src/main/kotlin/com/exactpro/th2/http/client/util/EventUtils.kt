package com.exactpro.th2.http.client.util

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.EventUtils
import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.common.utils.event.EventBatcher
import org.apache.commons.lang3.exception.ExceptionUtils

fun createEvent(
    name: String,
    type: String,
    messageId: MessageID? = null,
    cause: Throwable? = null
): Event = Event.start().apply {
    endTimestamp()
    name(name)
    type(type)
    status(if (cause != null) Event.Status.FAILED else Event.Status.PASSED)

    generateSequence(cause, Throwable::cause).forEach { error ->
        bodyData(EventUtils.createMessageBean(ExceptionUtils.getMessage(error)))
    }

    messageId?.let {
        messageID(it)
    }
}

fun EventBatcher.storeEvent(name: String, messageId: MessageID, parentEventId: String) {
    val event = createEvent(name, "info", messageId)
    onEvent(event.toProtoEvent(parentEventId))
}

fun EventBatcher.storeEvent(name: String, type: String, parentEventId: String, cause: Throwable? = null) {
    val event = createEvent(name, type, cause = cause)
    onEvent(event.toProtoEvent(parentEventId))
}