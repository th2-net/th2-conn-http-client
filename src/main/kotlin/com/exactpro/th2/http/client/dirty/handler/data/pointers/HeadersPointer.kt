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
import com.exactpro.th2.http.client.dirty.handler.parsers.HeaderValueParser
import io.netty.buffer.ByteBuf

class HeadersPointer(position: Int, private var length: Int , private val reference: ByteBuf, private val positions: MutableMap<String, HttpHeaderPosition>): Pointer(position), MutableMap<String, String> {

    private val cache: MutableMap<String, String?> = mutableMapOf()

    override fun get(key: String): String? {
        when(key) {
            in cache -> return cache[key]
            !in positions -> return null
        }

        return valueParser.parse(reference.readerIndex(positions[key]!!.start)).also {
            cache[key] = it
        }
    }

    override fun replace(key: String, value: String): String? = when {
        cache.contains(key) -> {
            replaceHeaderValue(key, cache[key]!!, value)
            value
        }
        positions.contains(key) -> {
            val oldValue = checkNotNull(get(key)) { "Replace method must get value by name due existence of pointer" }
            replaceHeaderValue(key, oldValue, value)
            value
        }
        else -> null
    }

    private fun replaceHeaderValue(key: String, oldValue: String, newValue: String) {
        val headerPosition = checkNotNull(positions[key])
        reference.readerIndex(headerPosition.start).replace(oldValue, newValue)
        (oldValue.length - newValue.length).also {
            expansion += it
            length += it
            headerPosition.end+=it
        }
        modified = true
        cache[key] = newValue
    }

    override fun put(key: String, value: String): String {
        if (replace(key, value) != null) return value
        cache[key] = value
        val additional = "$key: $value\r\n"
        val endOfHeaders = position + length
        reference.insert(additional, endOfHeaders)
        length+=additional.length
        expansion+=additional.length
        modified = true
        positions[key] = HttpHeaderPosition(endOfHeaders, endOfHeaders + additional.length)
        return value
    }

    private fun removeSingle(key: String): Boolean {
        if (!positions.contains(key)) {
            return false
        }
        cache.remove(key)
        val position = positions.remove(key)!!
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
        positions.forEach {
            it.value.let { currentPos ->
                if (currentPos.start > position) {
                    currentPos.start += amount
                    currentPos.end += amount
                }
            }

        }
    }

    override fun containsKey(key: String): Boolean = positions.containsKey(key)

    override fun containsValue(value: String): Boolean = error("Unsupported kind of operation")

    override fun isEmpty(): Boolean = positions.isEmpty()

    override fun clear() {
        positions.clear()
        cache.clear()
        reference.remove(position, position+length)
        length=0
        this.expansion-=length
        this.modified = true
    }

    override fun putAll(from: Map<out String, String>) = from.forEach {
        put(it.key, it.value)
    }

    override val size: Int
        get() = positions.size
    override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
        get() = error("Unsupported kind of operation")
    override val keys: MutableSet<String>
        get() = positions.keys
    override val values: MutableCollection<String>
        get() = error("Unsupported kind of operation")

    data class HttpHeaderPosition(var start: Int, var end: Int)

    companion object {
        val valueParser = HeaderValueParser()
    }
}