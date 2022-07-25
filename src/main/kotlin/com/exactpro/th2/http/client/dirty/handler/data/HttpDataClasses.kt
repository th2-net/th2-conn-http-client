package com.exactpro.th2.http.client.dirty.handler.data

import com.exactpro.th2.conn.dirty.tcp.core.util.insert
import com.exactpro.th2.conn.dirty.tcp.core.util.remove
import com.exactpro.th2.conn.dirty.tcp.core.util.replace
import com.exactpro.th2.http.client.dirty.handler.parsers.HeaderValueParser
import io.netty.buffer.ByteBuf

typealias NettyHttpMethod = io.netty.handler.codec.http.HttpMethod
typealias NettyHttpVersion = io.netty.handler.codec.http.HttpVersion

abstract class Pointer<T>(var position: Int, var length: Int, value: T, protected var modified: Boolean = false) {
    private var _value: T = value

    var expansion = 0
        protected set

    var value: T
        get() = _value
        set(value) {
            expansion+=getDifAmount(_value, value)
            _value = value
            modified = true
        }

    protected abstract fun getDifAmount(target:T, against: T): Int

    open fun move(amount: Int) {
        position+=amount
    }

    fun settle() {
        expansion = 0
        this.modified = false
    }

    fun isModified() = this.modified
}

class DirtyHttpMethod(position: Int, value: NettyHttpMethod): Pointer<NettyHttpMethod>(position, value.name().length, value) {
    override fun getDifAmount(target: NettyHttpMethod, against: NettyHttpMethod): Int = target.name().length - against.name().length
}

class DirtyHttpURL(position: Int, value: String): Pointer<String>(position, value.length, value) {
    override fun getDifAmount(target: String, against: String): Int = target.length - against.length
}

class DirtyHttpVersion(position: Int, value: NettyHttpVersion): Pointer<NettyHttpVersion>(position, value.text().length, value) {
    override fun getDifAmount(target: NettyHttpVersion, against: NettyHttpVersion): Int = target.text().length - against.text().length
}

class DirtyHttpBody(position: Int, value: ByteBuf): Pointer<ByteBuf>(position, value.writerIndex()-position, value) {
    override fun getDifAmount(target: ByteBuf, against: ByteBuf): Int = error("Rewrite of value for DirtyHttpBody isn't supported")
}

class HttpHeaderPosition(var start: Int, var end: Int)

class DirtyHttpHeaders(position: Int, length: Int , reference: ByteBuf, private val positions: MutableMap<String, HttpHeaderPosition>): Pointer<ByteBuf>(position, length, reference), MutableMap<String, String> {

    companion object {
        val valueParser = HeaderValueParser()
    }

    private val cache: MutableMap<String, String?> = mutableMapOf()

    override fun get(key: String): String? {
        when(key) {
            in cache -> return cache[key]
            !in positions -> return null
        }

        return valueParser.parse(value.readerIndex(positions[key]!!.start)).also {
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
        value.readerIndex(headerPosition.start).replace(oldValue, newValue)
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
        this.value.insert(additional, endOfHeaders)
        length+=additional.length
        expansion+=additional.length
        modified = true
        positions[key] = HttpHeaderPosition(endOfHeaders, endOfHeaders + additional.length)
        return value
    }

    fun removeSingle(key: String): Boolean {
        if (!positions.contains(key)) {
            return false
        }
        cache.remove(key)
        val position = positions.remove(key)!!
        value.readerIndex(position.start).remove(position.start, position.end)
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

    override fun getDifAmount(target: ByteBuf, against: ByteBuf): Int = error("Rewrite of value for DirtyHttpHeaderContainer isn't supported")

    override fun containsKey(key: String): Boolean = positions.containsKey(key)

    override fun containsValue(value: String): Boolean = error("Unsupported kind of operation")

    override fun isEmpty(): Boolean = positions.isEmpty()

    override fun clear() {
        positions.clear()
        cache.clear()
        this.value.remove(position, position+length)
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
}

class DirtyHttpRequest(private val httpMethod: DirtyHttpMethod, private val httpUrl: DirtyHttpURL, private val httpVersion: DirtyHttpVersion, private val httpBody: DirtyHttpBody, val headers: DirtyHttpHeaders, private val reference: ByteBuf) {

    var method: NettyHttpMethod
        get() = httpMethod.value
        set(value) = this.httpMethod.let {
            reference.replace(it.position, reference.writerIndex(), value.name())
            it.value = value
            settle()
        }

    var url: String
        get() = httpUrl.value
        set(value) = this.httpUrl.let {
            reference.replace(it.position, reference.writerIndex(), value)
            it.value = value
            settle()
        }

    var version: NettyHttpVersion
        get() = httpVersion.value
        set(value) = this.httpVersion.let {
            reference.replace(it.position, reference.writerIndex(), value.text())
            it.value = value
            settle()
        }

    var body: ByteBuf
        get() = httpBody.value.retain().readerIndex(httpBody.position)
        set(value) {
            this.httpBody.value.writerIndex(this.httpBody.position).writeBytes(value)
        }

    private fun settle() {
        var sum = 0
        if (httpMethod.isModified()) {
            sum += httpMethod.expansion
            httpMethod.settle()
        }
        if (httpUrl.isModified() || sum > 0) {
            sum = httpUrl.settleSingle(sum)
        }
        if (httpVersion.isModified() || sum > 0) {
            sum = httpVersion.settleSingle(sum)
        }
        if (headers.isModified() || sum > 0) {
            sum = headers.settleSingle(sum)
        }
        if (httpBody.isModified() || sum > 0) {
            sum = httpBody.settleSingle(sum)
        }
    }

    private fun Pointer<*>.settleSingle(amount: Int): Int {
        move(amount)
        return (expansion + amount).also {
            settle()
        }
    }
}