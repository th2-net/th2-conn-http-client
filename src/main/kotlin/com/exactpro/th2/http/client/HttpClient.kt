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
import rawhttp.core.IOSupplier
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.client.TcpRawHttpClient
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
    maxParallelRequests: Int,
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
        maxParallelRequests,
        host,
        port,
        onRequest
    )
) {
    private val logger = KotlinLogging.logger {}
    private val lock = ReentrantLock()
    private val rawHttp = RawHttp()


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
        when {
            !isRunning -> logger.info { "Client is already stopped" }
            else -> {
                logger.info { "Stopping client" }
                runCatching(onStop).onFailure { logger.error(it) { "Failed to execute onStop hook" } }
                options.close()
                isRunning = false
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

        val response = sendRequest(preparedRequest)

        response.runCatching {
            onResponse(preparedRequest, response)
        }.onFailure {
            logger.error(it) { "Failed to execute onResponse hook" }
        }

        return response
    }

    private fun sendRequest(request: RawHttpRequest): RawHttpResponse<Void> {
        val socket: Socket = options.getSocket(request.uri)

        try {
            val finalRequest = options.onRequest(request)
            val expectContinue = !finalRequest.startLine.httpVersion.isOlderThan(HttpVersion.HTTP_1_1) && finalRequest.expectContinue()

            val outputStream = socket.getOutputStream()
            val inputStream = socket.getInputStream()
            options.executorService.submit(requestSender(finalRequest, outputStream, expectContinue))

            val response = if (expectContinue) {
                val responseWaiter = ResponseWaiter { rawHttp.parseResponse(inputStream, finalRequest.startLine) }
                if (options.shouldContinue(responseWaiter)) {
                    finalRequest.body.get().writeTo(outputStream)
                    responseWaiter.call()
                } else {
                    throw RuntimeException("Unable to obtain a response due to a 100-continue " + "request not being continued")
                }
            } else {
                rawHttp.parseResponse(inputStream, finalRequest.startLine)
            }

            return when (response.statusCode) {
                100 -> {
                    options.onResponse(socket, finalRequest.uri, response)
                    options.onResponse(socket, finalRequest.uri, rawHttp.parseResponse(socket.getInputStream(), finalRequest.startLine))
                }
                else -> options.onResponse(socket, finalRequest.uri, response)
            }
        } catch (e: Exception) {
            logger.error(e) { "Removing socket due to network error: $socket" }
            options.removeSocket(socket)
            throw e
        }
    }

    override fun close() {
        if (isRunning) stop()
        super.close()
    }

    private class ResponseWaiter(private val readResponse: IOSupplier<RawHttpResponse<Void>>) : Callable<RawHttpResponse<Void>?> {
        val wasCalled = AtomicBoolean(false)

        @Volatile
        lateinit var response: RawHttpResponse<Void>

        override fun call(): RawHttpResponse<Void> {
            return if (wasCalled.compareAndSet(false, true)) {
                readResponse.get().also {
                    response = it
                }
            } else {
                response
            }
        }
    }
}

