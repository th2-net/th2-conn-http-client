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

package com.exactpro.th2.http.client

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.common.schema.dictionary.DictionaryType
import com.exactpro.th2.conn.dirty.tcp.core.api.IChannel
import com.exactpro.th2.conn.dirty.tcp.core.api.IContext
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings
import com.exactpro.th2.conn.dirty.tcp.core.api.impl.Channel
import com.exactpro.th2.http.client.dirty.handler.HttpHandler
import com.exactpro.th2.http.client.dirty.handler.HttpHandlerSettings
import com.exactpro.th2.http.client.dirty.handler.stateapi.IState
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import mu.KotlinLogging
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.lang.StringBuilder
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.util.concurrent.Future

class HandlerTests {

    @Test
    fun `single response`() {
        Assertions.assertTrue(createHandler().onReceive(Unpooled.buffer().writeBytes(httpResponse.toByteArray()))!=null)
    }

    @Test
    fun `chunked response`() {
        createHandler().testResponse(httpResponse, 5, 1)
    }

    @Test
    fun `chunked response with part of new one`() {
        createHandler().testResponse(httpResponse + "HTTP/1.1", 5, 1)
    }

    @Test
    fun `few chunked responses with part of new one`() {
        createHandler().testResponse(httpResponse.repeat(3) + "HTTP/1.1", 15, 3)
    }

    @Test
    fun `ten chunked responses`() {
        createHandler().testResponse(httpResponse.repeat(10), 5, 10)
    }

    @Test
    fun `ten responses`() {
        createHandler().testResponse(httpResponse.repeat(10), 0, 10)
    }

    @Test
    fun `ten responses with part of new one`() {
        createHandler().testResponse(httpResponse.repeat(10) + "HTTP/1.1", 0, 10)
    }

    @Test
    fun `single response with part of new one`() {
        createHandler().testResponse(httpResponse + "HTTP/1.1", 0, 1)
    }

    private fun HttpHandler.testResponse(data: String, chunkSize: Int, expectCount: Int) {
        var resultCount = 0

        if (chunkSize == 0) {
            val byteBuf = Unpooled.buffer().writeBytes(data.toByteArray())
            while (byteBuf.isReadable) {
                onReceive(byteBuf) ?: break
                resultCount++
            }
        } else {
            val chunks = data.chunked(chunkSize)
            val buffer = StringBuilder()
            chunks.forEach { chunk ->
                val byteBuf = Unpooled.buffer().writeBytes(buffer.append(chunk).toString().toByteArray())
                while (byteBuf.isReadable) {
                    onReceive(byteBuf) ?: break
                    resultCount++
                }

                buffer.clear()
                if (byteBuf.readableBytes() > 0) {
                    buffer.append(byteBuf.readCharSequence(byteBuf.readableBytes(), Charset.defaultCharset()))
                }
                byteBuf.release()
            }
        }

        Assertions.assertEquals(expectCount, resultCount) {"Test with:\n$data\nmust have been recognized as $expectCount messages"}
        LOGGER.info { "Test with $expectCount messages was passed" }
    }

    private fun createHandler(): HttpHandler {
        val context = object : IContext<IProtocolHandlerSettings> {
            override val channel: IChannel
                get() = object : IChannel {
                    override val address: InetSocketAddress
                        get() = InetSocketAddress("testHost", 25565)
                    override val isOpen: Boolean
                        get() = true
                    override val security: Channel.Security
                        get() = error("Not yet implemented")

                    override fun close() = Unit
                    override fun open() = Unit
                    override fun open(address: InetSocketAddress, security: Channel.Security) = Unit

                    override fun send(message: ByteBuf, metadata: Map<String, String>, mode: IChannel.SendMode): Future<MessageID> {
                        error("Not yet implemented")
                    }

                }
            override val settings: IProtocolHandlerSettings
                get() = error("Not yet implemented")

            override fun get(dictionary: DictionaryType): InputStream {
                error("Not yet implemented")
            }

            override fun send(event: Event) {
                error("Not yet implemented")
            }

        }
        val state = object : IState {}
        return HttpHandler(context, state, HttpHandlerSettings())
    }

    companion object {
        private val LOGGER = KotlinLogging.logger { this::class.java.simpleName }
        val httpResponse = """
            HTTP/1.1 200 OK 
            Content-Type: plain/text
            Content-Length: 205
            
            { "id" : 901, "name" : { "first":"Tom", "middle":"and", "last":"Jerry" }, "phones" : [ {"type" : "home", "number" : "1233333" }, {"type" : "work", "number" : "264444" }], "lazy" : false, "married" : null }
        """.trimIndent()
    }

}