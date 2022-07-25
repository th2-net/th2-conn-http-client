/*
 * Copyright 2022-2022 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.http.client.dirty.handler

import com.exactpro.th2.http.client.dirty.handler.data.pointers.HeadersPointer
import io.netty.buffer.ByteBuf
import io.netty.util.internal.AppendableCharSequence
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

private fun Char.isSPLenient(): Boolean {
    // See https://tools.ietf.org/html/rfc7230#section-3.5
    return this == ' ' || this == 0x09.toChar() || this == 0x0B.toChar() || this == 0x0C.toChar() || this == 0x0D.toChar()
}

private fun AppendableCharSequence.findSPLenient(offset: Int): Int {
    for (result in offset until length) {
        if (charAtUnsafe(result).isSPLenient()) {
            return result
        }
    }
    return length
}

fun ByteBuf.forEachByteIndexed(byteProcessor: (index: Int, byte: Byte) -> Boolean): Int {
    var index = 0
    return this.forEachByte {
        byteProcessor(index++, it)
    }
}

fun HeadersPointer.HttpHeaderPosition.move(step: Int) {
    this.start += step
    this.end += step
}