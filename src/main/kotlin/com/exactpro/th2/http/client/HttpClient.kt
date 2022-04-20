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

import com.exactpro.th2.http.client.util.Certificate
import com.exactpro.th2.http.client.util.getSocketFactory
import mu.KotlinLogging
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.client.TcpRawHttpClient
import java.io.IOException
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class HttpClient(
    https: Boolean,
    private val host: String,
    private val port: Int,
    readTimeout: Int,
    keepAliveTimeout: Long,
    socketCapacity: Int,
    private val defaultHeaders: Map<String, List<String>>,
    private val prepareRequest: (RawHttpRequest) -> RawHttpRequest,
    onRequest: (RawHttpRequest) -> Unit,
    private val onResponse: (RawHttpRequest, RawHttpResponse<*>) -> Unit,
    private val onStart: () -> Unit = {},
    private val onStop: () -> Unit = {},
    validateCertificates: Boolean = true,
    clientCertificate: Certificate? = null
) : TcpRawHttpClient(
    ClientOptions(
        readTimeout,
        keepAliveTimeout,
        getSocketFactory(https, validateCertificates, clientCertificate),
        socketCapacity,
        onRequest
    )
) {
    private val logger = KotlinLogging.logger {}
    private val lock = ReentrantLock()

    private val rawHttp = RawHttp()
    private val clientOptions = options as ClientOptions


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
                clientOptions.removeSockets()
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
            throw IllegalStateException("Failed to prepare request: $request", it)
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

        val finalRequest = options.onRequest(preparedRequest)
        val socket: Socket = finalRequest.uri.runCatching(clientOptions::getAndLockSocket).getOrElse {
            when (it) {
                is IOException -> throw IOException("Can't handle socket by uri: ${finalRequest.uri}", it)
                else -> throw it
            }
        }

        val response = runCatching {
            send(finalRequest, socket)
        }.getOrElse { cause ->
            logger.error(cause) { "Removing socket due to network error: $socket" }
            options.removeSocket(socket)
            throw cause
        }

        clientOptions.freeSocket(socket)

        response.runCatching {
            onResponse(preparedRequest, response)
        }.onFailure {
            logger.error(it) { "Failed to execute onResponse hook" }
        }

        return response
    }

    private fun send(request: RawHttpRequest, socket: Socket): RawHttpResponse<Void> {
        val expectContinue = !request.startLine.httpVersion.isOlderThan(HttpVersion.HTTP_1_1) && request.expectContinue()

        val outputStream = socket.getOutputStream()
        val inputStream = socket.getInputStream()

        options.executorService.submit(requestSender(request, outputStream, expectContinue))

        val response: RawHttpResponse<Void> = if (expectContinue) {
            val responseWaiter = ResponseCache { rawHttp.parseResponse(inputStream, request.startLine) }
            if (options.shouldContinue(responseWaiter)) {
                request.body.get().writeTo(outputStream)
                if (!responseWaiter.cached.get()) responseWaiter.call() else responseWaiter.response
            } else {
                throw RuntimeException("Unable to obtain a response due to a 100-continue, request not being continued")
            }
        } else {
            rawHttp.parseResponse(inputStream, request.startLine)
        }.let {
            if (RawHttpResponse.shouldCloseConnectionAfter(it)) {
                options.removeSocket(socket)
                it.eagerly(false)
            } else {
                it.eagerly()
            }
        }

        return if (response.statusCode == 100) {
            // 100-Continue: ignore the first response, then expect a new one...
            options.onResponse(socket, request.uri, response)
            options.onResponse(socket, request.uri, rawHttp.parseResponse(socket.getInputStream(), request.startLine))
        } else {
            options.onResponse(socket, request.uri, response)
        }
    }

    override fun close() {
        if (isRunning) stop()
        super.close()
    }
}

private class ResponseCache constructor(private val readResponse: () -> RawHttpResponse<Void>) : Callable<RawHttpResponse<Void>?> {
    val cached = AtomicBoolean(false)

    @Volatile
    lateinit var response: RawHttpResponse<Void>

    override fun call(): RawHttpResponse<Void> = when {
        cached.compareAndSet(false, true) -> readResponse().also { response = it }
        else -> error("Cannot receive HTTP Request more than once")
    }
}