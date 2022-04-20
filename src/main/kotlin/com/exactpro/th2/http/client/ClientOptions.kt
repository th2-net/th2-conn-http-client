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
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.client.TcpRawHttpClient
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.net.SocketFactory
import kotlin.concurrent.withLock

internal class ClientOptions(
    private val readTimeout: Int,
    private val keepAliveTimeout: Long,
    private val socketFactory: SocketFactory,
    socketCapacity: Int,
    private val onRequest: (RawHttpRequest) -> Unit,
) : TcpRawHttpClient.DefaultOptions() {
    private val logger = KotlinLogging.logger {}
    private val socketExpirationTimes = mutableMapOf<Socket, Long>()
    private val lock = ReentrantLock()

    private val socketsReady: MutableMap<Socket, Boolean> = ConcurrentHashMap(socketCapacity)


    override fun onRequest(httpRequest: RawHttpRequest): RawHttpRequest {
        logger.info { "onRequest: $httpRequest" }
        httpRequest.runCatching(onRequest).onFailure { logger.error(it) { "Failed to execute onRequest hook" } }
        return super.onRequest(httpRequest)
    }

    override fun onResponse(socket: Socket, uri: URI, httpResponse: RawHttpResponse<Void>): RawHttpResponse<Void> {
        logger.debug { "onResponse: $socket: $uri" }

        val response = httpResponse.eagerly()
        logger.info { "Received response: $socket: $response" }
        return response
    }

    override fun createSocket(useHttps: Boolean, host: String, port: Int): Socket = lock.withLock {
        socketFactory.createSocket(host, port).also {
            logger.debug { "Created socket $it" }
        }
    }

    private fun createOrGetSocket(uri: URI): Socket  {
        val host = checkNotNull(uri.host) { "Host is not available in the URI" }
        var socket = socketsReady.entries.firstOrNull { it.value }?.key

        if (socket != null && (socket.isClosed || !socket.isConnected)) {
            socketsReady.remove(socket)
            socket = null
        }

        if (socket == null) {
            val useHttps = "https".equals(uri.scheme, ignoreCase = true)
            val port = when {
                uri.port < 1 -> if (useHttps) 443 else 80
                else -> uri.port
            }
            socket = checkNotNull(try {
                createSocket(useHttps, host, port).apply {
                    soTimeout = 5000
                }
            } catch (e: IOException) {
                throw RuntimeException(e)
            }) { "Socket wasn't initialized" }

            socketsReady[socket] = true
        }
        return socket
    }

    fun getAndLockSocket(uri: URI): Socket = getSocket(uri).also {
        socketsReady[it] = false
        logger.debug { "Socked was locked $it" }
    }

    fun freeSocket(socket: Socket) {
        socketsReady[socket] = true
        logger.debug { "Socked was released $socket" }
    }

    override fun getSocket(uri: URI): Socket = createOrGetSocket(uri).let { socket ->
        val currentTime = System.currentTimeMillis()

        socketExpirationTimes[socket]?.let { expirationTime ->
            if (currentTime > expirationTime) {
                logger.debug { "Removing inactive socket: $socket (expired at: ${Instant.ofEpochMilli(expirationTime)})" }
                removeSocket(socket)
                return getSocket(uri)
            }
        }

        socketExpirationTimes[socket] = currentTime + keepAliveTimeout
        socket.apply { soTimeout = readTimeout }
    }

    override fun removeSocket(socket: Socket) {
        socket.runCatching { close() }
        socketsReady.remove(socket)
        socketExpirationTimes -= socket
    }

    override fun shouldContinue(waitForHttpResponse: Callable<RawHttpResponse<Void>>): Boolean {
        return executorService.submit(waitForHttpResponse).runCatching {
            val response = this[5, TimeUnit.SECONDS]
            response.statusCode == 100 || response.statusCode in 200..399
        }.onFailure { logger.error(it) {} }.getOrElse { false }
    }

    fun removeSockets() = socketsReady.keys.forEach(this::removeSocket)
}
