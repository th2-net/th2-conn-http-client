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

package com.exactpro.th2.http.client.api.impl

import com.exactpro.th2.http.client.api.IStateManager
import com.exactpro.th2.http.client.api.IStateManager.StateManagerContext
import com.google.auto.service.AutoService
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.client.RawHttpClient
import java.util.Base64
import kotlin.text.Charsets.UTF_8

@AutoService(IStateManager::class)
class BasicStateManager : IStateManager {
    private lateinit var client: RawHttpClient<*>
    private var settings: BasicAuthSettings? = null

    override fun init(context: StateManagerContext) {
        check(!::client.isInitialized) { "State manager is already initialized" }
        this.client = context.httpClient
        this.settings = context.authSettings?.run {
            checkNotNull(this as? BasicAuthSettings) { "context.${StateManagerContext::authSettings} is not an instance of ${BasicAuthSettings::class.simpleName}" }
        }
    }

    override fun onStart() {}

    override fun prepareRequest(request: RawHttpRequest): RawHttpRequest = settings?.run {
        val credentials = "${username}:${password}".toByteArray(UTF_8)
        val authHeader = "Basic ${Base64.getEncoder().encodeToString(credentials)}"
        val headersWithAuth = RawHttpHeaders.newBuilder(request.headers).overwrite("Authorization", authHeader).build()
        request.withHeaders(headersWithAuth)
    } ?: request

    override fun onResponse(response: RawHttpResponse<*>) {}

    override fun close() {}
}