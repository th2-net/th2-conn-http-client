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
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.withLock

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
    private val onStart: () -> Unit = {},
    private val onStop: () -> Unit = {},
    validateCertificates: Boolean = true
) : TcpRawHttpClient(ClientOptions(https, readTimeout, keepAliveTimeout, validateCertificates, onRequest)) {
    private val logger = KotlinLogging.logger {}
    private val lock = ReentrantLock()

    @Volatile var isRunning: Boolean = false
        private set

    fun start() = lock.withLock {
        when (isRunning) {
            true -> logger.info { "Client is already started" }
            else -> {
                logger.info { "Starting client" }
                isRunning = true
                runCatching(onStart).onFailure {
                    isRunning = false
                    throw IllegalStateException("Failed to execute onStart hook", it)
                }
                logger.info { "Started client" }
            }
        }
    }

    fun stop() = lock.withLock {
        when (!isRunning) {
            true -> logger.info { "Client is already stopped" }
            else -> {
                logger.info { "Stopping client" }
                runCatching(onStop).onFailure { logger.error(it) { "Failed to execute onStop hook" } }
                (options as ClientOptions).removeSockets()
                isRunning = false
                logger.info { "Stopped client" }
            }
        }
    }

    override fun send(request: RawHttpRequest): RawHttpResponse<Void> {
        if (!isRunning) start()

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

        val response = runCatching {
            super.send(preparedRequest)
        }.getOrElse { cause ->
            val socket = options.getSocket(preparedRequest.uri)
            logger.error(cause) { "Removing socket due to network error: $socket" }
            options.removeSocket(socket)
            throw cause
        }

        response.runCatching {
            onResponse(preparedRequest, response)
        }.onFailure {
            logger.error(it) { "Failed to execute onResponse hook" }
        }

        return response
    }

    override fun close() {
        if (isRunning) stop()
        super.close()
    }
}

private class ClientOptions(
    private val https: Boolean,
    private val readTimeout: Int,
    private val keepAliveTimeout: Long,
    private val validateCertificates: Boolean,
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

    override fun createSocket(useHttps: Boolean, host: String, port: Int): Socket = when {
        validateCertificates || !https -> super.createSocket(https, host, port)
        else -> INSECURE_SOCKET_FACTORY.createSocket(host, port)
    }

    override fun getSocket(uri: URI): Socket = super.getSocket(uri).let { socket ->
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
        super.removeSocket(socket)
        socketExpirationTimes -= socket
    }

    fun removeSockets() = socketExpirationTimes.keys.removeIf {
        removeSocket(it)
        true
    }

    companion object {
        private val INSECURE_TRUST_MANAGER = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        private val INSECURE_SOCKET_FACTORY = SSLContext.getInstance("TLS").run {
            init(null, arrayOf(INSECURE_TRUST_MANAGER), null)
            socketFactory
        }
    }
}