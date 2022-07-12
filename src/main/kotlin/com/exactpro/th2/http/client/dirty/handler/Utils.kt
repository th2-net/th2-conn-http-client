package com.exactpro.th2.http.client.dirty.handler

import com.exactpro.th2.common.schema.configuration.ConfigurationManager.Companion.LOGGER
import io.netty.buffer.ByteBuf
import java.nio.charset.Charset
import java.util.ServiceLoader

inline fun <reified T> load(defaultImpl: Class<out T>): T {
    val instances = ServiceLoader.load(T::class.java).toList()

    return when (instances.size) {
        0 -> error("No instances of ${T::class.simpleName}")
        1 -> instances.first()
        2 -> instances.first { !defaultImpl.isInstance(it) }
        else -> error("More than 1 non-default instance of ${T::class.simpleName} has been found: $instances")
    }
}

fun ByteBuf.retainedSliceLine(): ByteBuf? {
    if (!isReadable) {
        return null
    }
    val from = readerIndex()
    val separator = '\n'.toByte()

    while(isReadable && readByte() != separator) {}

    return retainedSlice(from, readerIndex() - from).apply {
        val oldReaderIndex = readerIndex()
        LOGGER.debug { "Sliced data with reader index $oldReaderIndex: ${toString(Charset.defaultCharset())}" }
        readerIndex(oldReaderIndex)
    }
}