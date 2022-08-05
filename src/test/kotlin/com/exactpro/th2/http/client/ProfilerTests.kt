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

import com.exactpro.th2.conn.dirty.tcp.core.api.IChannel
import com.exactpro.th2.http.client.dirty.handler.HttpHandler
import com.exactpro.th2.http.client.dirty.handler.HttpHandlerSettings
import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpRequest
import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpResponse
import com.exactpro.th2.http.client.dirty.handler.stateapi.IState
import com.exactpro.th2.http.client.util.TestContext
import com.exactpro.th2.http.client.util.createClient
import io.netty.buffer.Unpooled
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RequestLine
import java.net.URI
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger

class ProfilerTests {

    /* Comment annotation after use */
    //@Test
    fun `simple test`() {
        val port = 25565
        val pauseBetweenRequests = 200L
        val testContext = TestContext(HttpHandlerSettings().apply {
            this.defaultHeaders = mapOf()
        })

        val state = object : IState {
            val requests = AtomicInteger(0)
            val responses = AtomicInteger(0)
            override fun onResponse(response: DirtyHttpResponse) {
                responses.incrementAndGet()
            }

            override fun onRequest(request: DirtyHttpRequest) {
                requests.incrementAndGet()
            }
        }

        val client = createClient(HttpHandler(testContext, state, testContext.settings as HttpHandlerSettings), 10, port, true).apply { open() }
        testContext.init(client)

        val request = createRequest(port).toString().toByteArray(Charset.defaultCharset())

        while (true) {
            client.send(Unpooled.buffer().writeBytes(request), mapOf(), IChannel.SendMode.HANDLE)
            Thread.sleep(pauseBetweenRequests)
        }

    }

    private fun createRequest(port: Int) = RawHttpRequest(RequestLine("GET", URI("/test"), HttpVersion.HTTP_1_1), RawHttpHeaders.newBuilder()
        .apply {
            with("Host", "localhost:$port")
            with("content-length", "0")
        }
        .build(), null, null)

}