/*
 * Copyright 2020-2022 Exactpro (Exactpro Systems Limited)
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
import rawhttp.core.EagerHttpResponse
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.client.TcpRawHttpClient
import java.io.Closeable
import java.net.Socket
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.net.SocketFactory
import kotlin.concurrent.withLock

internal class ClientOptions(
    private val readTimeout: Int,
    keepAliveTimeout: Long,
    private val socketFactory: SocketFactory,
    socketCapacity: Int,
    host: String,
    port: Int,
    private val onRequest: (RawHttpRequest) -> Unit,
) : TcpRawHttpClient.DefaultOptions() {
    private val logger = KotlinLogging.logger {}
    private val lock = ReentrantLock()

    private val socketPool: SocketPool = SocketPool(host,port, keepAliveTimeout, socketCapacity) { host, port ->
        createSocket(true, host, port)
    }

    override fun onRequest(httpRequest: RawHttpRequest): RawHttpRequest {
        logger.info { "Sending request: $httpRequest" }
        httpRequest.runCatching(onRequest).onFailure { logger.error(it) { "Failed to execute onRequest hook" } }
        return httpRequest
    }

    override fun onResponse(socket: Socket, uri: URI, httpResponse: RawHttpResponse<Void>): EagerHttpResponse<Void> =  try {
        httpResponse.eagerly()
    } catch (e: Throwable) {
        throw IllegalStateException("Cannot read http response eagerly during onResponse call", e)
    }.also {
        logger.info { "Received response on socket '$socket': $it" }
        if (RawHttpResponse.shouldCloseConnectionAfter(it)) {
            removeSocket(socket)
        } else {
            socketPool.release(socket)
        }
    }

    override fun createSocket(useHttps: Boolean, host: String, port: Int): Socket = lock.withLock {
        socketFactory.createSocket(host, port).also {
            it.soTimeout = readTimeout
            logger.debug { "Created socket $it" }
        }
    }

    override fun getSocket(uri: URI): Socket = socketPool.acquire()

    override fun removeSocket(socket: Socket) = socketPool.closeSocket(socket)

    override fun shouldContinue(waitForHttpResponse: Callable<RawHttpResponse<Void>>): Boolean {
        return executorService.submit(waitForHttpResponse).runCatching {
            val response = this[5, TimeUnit.SECONDS]
            response.statusCode == 100 || response.statusCode in 200..399
        }.onFailure { logger.error(it) {} }.getOrElse { false }
    }

    override fun close() {
        socketPool.close()
    }

    private class SocketPool(
        private val host: String,
        private val port: Int,
        private val keepAliveTimeout: Long,
        capacity: Int,
        private val factory: (host: String, port: Int) -> Socket,
    ) : Closeable {
        private val logger = KotlinLogging.logger { SocketPool::class.simpleName }
        private val semaphore = Semaphore(capacity)
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val expirationTimes = ConcurrentHashMap<Socket, Long>()

        fun acquire(): Socket {
            semaphore.acquire()

            var socket = sockets.poll() ?: factory(host, port)
            val currentTime = System.currentTimeMillis()
            val expirationTime = expirationTimes.getOrPut(socket) { currentTime + keepAliveTimeout }

            if(expirationTime < currentTime) {
                expirationTimes -= socket
                socket = factory(host, port)
            }

            return socket.apply {
                expirationTimes[socket] = currentTime + keepAliveTimeout
            }
        }

        fun release(socket: Socket) {
            sockets.offer(socket)
            semaphore.release()
        }

        fun closeSocket(socket: Socket) {
            sockets.remove(socket)
            expirationTimes.remove(socket)
            tryClose(socket)
        }

        override fun close() {
            sockets.forEach(this::tryClose)
            sockets.clear()
            expirationTimes.clear()

        }

        fun tryClose(socket: Socket) = socket.runCatching(Socket::close).onFailure { error ->
            logger.warn(error) { "Cannot close socket: $this" }
        }

    }
}
