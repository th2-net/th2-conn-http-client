/*
 * Copyright 2020-2021 Exactpro (Exactpro Systems Limited)
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

import mu.KotlinLogging
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.client.TcpRawHttpClient
import rawhttp.core.client.TcpRawHttpClient.DefaultOptions
import java.net.Socket
import java.net.URI
import java.time.Instant

class HttpClient(
    https: Boolean,
    private val host: String,
    private val port: Int,
    readTimeout: Int,
    keepAliveTimeout: Long,
    private val defaultHeaders: Map<String, List<String>>,
    private val prepareRequest: (RawHttpRequest) -> RawHttpRequest,
    onRequest: (RawHttpRequest) -> Unit,
    private val onResponse: (RawHttpRequest, RawHttpResponse<*>) -> Unit,
) : TcpRawHttpClient(ClientOptions(https, readTimeout, keepAliveTimeout, onRequest)) {
    private val logger = KotlinLogging.logger {}

    val isRunning: Boolean
        get() = !options.executorService.isShutdown

    override fun send(request: RawHttpRequest): RawHttpResponse<Void> {
        val sendRequest = request.run {
            when {
                host != uri.host || port != uri.port -> withRequestLine(startLine.withHost("$host:$port"))
                else -> this
            }
        }

        val preparedRequest = sendRequest.runCatching(prepareRequest).getOrElse {
            throw IllegalStateException("Failed to prepare request: ${request.eagerly()}", it)
        }.run {
            when {
                defaultHeaders.isEmpty() -> this
                defaultHeaders.keys.all(headers::contains) -> this
                else -> withHeaders(RawHttpHeaders.newBuilder(headers).run {
                    defaultHeaders.forEach { (header, values) ->
                        if (!headers.contains(header)) {
                            values.forEach { value -> with(header, value) }
                        }
                    }
                    build()
                })
            }
        }

        val response = super.send(preparedRequest)

        response.runCatching {
            onResponse(preparedRequest, response)
        }.onFailure {
            logger.error(it) { "Failed to execute onResponse hook" }
        }

        return response
    }
}

private class ClientOptions(
    private val https: Boolean,
    private val readTimeout: Int,
    private val keepAliveTimeout: Long,
    private val onRequest: (RawHttpRequest) -> Unit,
) : DefaultOptions() {
    private val logger = KotlinLogging.logger {}
    private val socketExpirationTimes = mutableMapOf<Socket, Long>()

    override fun onRequest(httpRequest: RawHttpRequest): RawHttpRequest {
        val request = httpRequest.eagerly()
        logger.debug { "Sent request: $request" }
        request.runCatching(onRequest).onFailure { logger.error(it) { "Failed to execute onRequest hook" } }
        return super.onRequest(request)
    }

    override fun onResponse(socket: Socket, uri: URI, httpResponse: RawHttpResponse<Void>): RawHttpResponse<Void> {
        val response = httpResponse.eagerly()
        logger.debug { "Received response: $response" }
        return super.onResponse(socket, uri, response)
    }

    override fun createSocket(useHttps: Boolean, host: String, port: Int): Socket {
        return super.createSocket(https, host, port)
    }

    override fun getSocket(uri: URI): Socket = super.getSocket(uri).let { socket ->
        val currentTime = System.currentTimeMillis()

        socketExpirationTimes[socket]?.let { expirationTime ->
            if (currentTime > expirationTime) {
                logger.debug { "Removing inactive socket: $socket (expired at: ${Instant.ofEpochMilli(expirationTime)})" }
                socketExpirationTimes -= socket
                socket.runCatching { close() }
                removeSocket(socket)
                return getSocket(uri)
            }
        }

        socketExpirationTimes[socket] = currentTime + keepAliveTimeout
        socket.apply { soTimeout = readTimeout }
    }

    override fun removeSocket(socket: Socket) {
        socketExpirationTimes -= socket
    }
}
