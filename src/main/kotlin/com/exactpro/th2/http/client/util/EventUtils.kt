package com.exactpro.th2.http.client.util

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.EventUtils
import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.common.utils.event.EventBatcher
import org.apache.commons.lang3.exception.ExceptionUtils

fun createEvent(name: String, messageId: MessageID): Event = Event.start().apply {
    endTimestamp()
    name(name)
    type("Info")
    status(Event.Status.PASSED)

    messageID(messageId)
}

fun createEvent(
    name: String,
    type: String,
    cause: Throwable? = null
): Event = Event.start().apply {
    endTimestamp()
    name(name)
    type(type)
    status(if (cause != null) Event.Status.FAILED else Event.Status.PASSED)

    var error = cause

    while (error != null) {
        bodyData(EventUtils.createMessageBean(ExceptionUtils.getMessage(error)))
        error = error.cause
    }
}

fun EventBatcher.storeEvent(name: String, messageId: MessageID, parentEventId: String): Event {
    val event = createEvent(name, messageId)
    onEvent(event.toProtoEvent(parentEventId))
    return event
}

fun EventBatcher.storeEvent(name: String, type: String, parentEventId: String, cause: Throwable? = null): Event {
    val event = createEvent(name, type, cause)
    onEvent(event.toProtoEvent(parentEventId))
    return event
}