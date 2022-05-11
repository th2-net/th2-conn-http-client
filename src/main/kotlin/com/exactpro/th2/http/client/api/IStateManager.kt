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

package com.exactpro.th2.http.client.api

import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.client.RawHttpClient

/**
 * Represents an entity which manages client's state (e.g. performs authorization, sends heartbeats, etc.)
 */
interface IStateManager : AutoCloseable {
    /**
     * Initializes state manager with provided [context]
     */
    fun init(context: StateManagerContext)

    /**
     * Performs start-up process (e.g. authorization)
     */
    fun onStart()

    /**
     * Prepares [request] before sending (e.g. applies authorization headers)
     */
    fun prepareRequest(request: RawHttpRequest): RawHttpRequest

    /**
     * Processes received [response] to maintain state (e.g. to detect logout)
     */
    fun onResponse(response: RawHttpResponse<*>)

    /**
     * Performs shutdown process (e.g. logout)
     */
    fun onStop() {}

    data class StateManagerContext(
        val httpClient: RawHttpClient<*>,
        val authSettings: IAuthSettings?
    )
}