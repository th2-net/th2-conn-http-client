/*
 * Copyright 2020-2023 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.http.client.api

import com.exactpro.th2.common.grpc.MessageGroup
import rawhttp.core.client.RawHttpClient
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageGroup as TransportMessageGroup

/**
 * Represents an entity which handles incoming send requests
 */
interface IRequestHandler : AutoCloseable {
    /**
     * Initializes request handler with provided [context]
     */
    fun init(context: RequestHandlerContext)

    /**
     * Processes send [request] in form of a message group in protobuf protocol
     * Batch represents a single HTTP request and contains at most 2 messages in it - one for headers (parsed) and the other one for body (raw - if present)
     */
    fun onRequest(request: MessageGroup) {
        throw UnsupportedOperationException("Process request in protobuf format is unsupported")
    }

    /**
     * Processes send [request] in form of a message group in transport protocol
     * Batch represents a single HTTP request and contains at most 2 messages in it - one for headers (parsed) and the other one for body (raw - if present)
     */
    fun onRequest(request: TransportMessageGroup) {
        throw UnsupportedOperationException("Process request in protobuf format is unsupported")
    }

    data class RequestHandlerContext(val httpClient: RawHttpClient<*>)
}