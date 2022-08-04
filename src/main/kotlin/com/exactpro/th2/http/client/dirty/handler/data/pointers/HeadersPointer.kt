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


package com.exactpro.th2.http.client.dirty.handler.data.pointers

import com.exactpro.th2.conn.dirty.tcp.core.util.insert
import com.exactpro.th2.conn.dirty.tcp.core.util.remove
import com.exactpro.th2.conn.dirty.tcp.core.util.replace
import io.netty.buffer.ByteBuf

class HeadersPointer(position: Int, private var length: Int , private val reference: ByteBuf, private val details: MutableMap<String, HttpHeaderDetails>): Pointer(position), MutableMap<String, String> {

    override fun get(key: String): String? = details[key]?.value



    override fun replace(key: String, value: String): String? = if (!details.containsKey(key)) {
        this[key] = value
        null
    } else {
        details[key]!!.also { header ->
            (header.value.length - value.length).also { amount ->
                expansion += amount
                length += amount
            }
            reference.readerIndex(header.start).replace(header.value, value)
            modified = true
        }.value
    }

    override fun put(key: String, value: String): String = if (!details.containsKey(key)) {
        val additional = "$key: $value\r\n"
        val endOfHeaders = position + length
        reference.insert(additional, endOfHeaders)
        length+=additional.length
        expansion+=additional.length
        modified = true
        details[key] = HttpHeaderDetails(endOfHeaders, endOfHeaders + additional.length, value)
        value
    } else {
        replace(key, value)
        value
    }

    private fun removeSingle(key: String): Boolean {
        if (!details.contains(key)) {
            return false
        }
        val position = details.remove(key)!!
        reference.readerIndex(position.start).remove(position.start, position.end)
        moveAfter(position.start, position.start-position.end)
        modified = true
        length-=position.end-position.start
        return true
    }

    override fun remove(key: String): String? = get(key).also {
        removeSingle(key)
    }

    private fun moveAfter(position: Int, amount: Int) {
        details.forEach {
            it.value.let { currentPos ->
                if (currentPos.start > position) {
                    currentPos.start += amount
                    currentPos.end += amount
                }
            }

        }
    }

    override fun containsKey(key: String): Boolean = details.containsKey(key)

    override fun containsValue(value: String): Boolean = error("Unsupported kind of operation")

    override fun isEmpty(): Boolean = details.isEmpty()

    override fun clear() {
        details.clear()
        reference.remove(position, position+length)
        length=0
        this.expansion-=length
        this.modified = true
    }

    override fun putAll(from: Map<out String, String>) = from.forEach {
        put(it.key, it.value)
    }

    override val size: Int
        get() = details.size
    override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
        get() = error("Unsupported kind of operation")
    override val keys: MutableSet<String>
        get() = details.keys
    override val values: MutableCollection<String>
        get() = error("Unsupported kind of operation")

    data class HttpHeaderDetails(var start: Int, var end: Int, var value: String)
}