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

package com.exactpro.th2.http.client.api.impl

import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.http.client.HttpClient
import com.exactpro.th2.http.client.api.IRequestHandler
import com.exactpro.th2.http.client.api.IRequestHandler.RequestHandlerContext
import com.exactpro.th2.http.client.util.toRequest
import com.google.auto.service.AutoService
import com.exactpro.th2.common.schema.message.impl.rabbitmq.transport.MessageGroup as TransportMessageGroup

@AutoService(IRequestHandler::class)
class BasicRequestHandler : IRequestHandler {
    private lateinit var client: HttpClient

    override fun init(context: RequestHandlerContext) {
        check(!::client.isInitialized) { "Request handler is already initialized" }
        this.client = context.httpClient as HttpClient
    }

    override fun onRequest(request: MessageGroup) {
        client.send(request.toRequest())
    }

    override fun onRequest(request: TransportMessageGroup) {
        client.send(request.toRequest())
    }

    override fun close() {}
}