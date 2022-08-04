/*
 * Copyright 2022-2022 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.http.client.dirty.handler.stateapi

import com.exactpro.th2.conn.dirty.tcp.core.api.IChannel
import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpRequest
import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpResponse

interface IState: AutoCloseable {
    fun onOpen() = Unit
    fun onRequest(request: DirtyHttpRequest) = Unit
    fun onResponse(response: DirtyHttpResponse) = Unit
    fun onClose() = Unit
    override fun close() = Unit
}

interface IStateSettings

interface IStateFactory {
    /**
     * Returns factory name
     */
    val name: String

    /**
     * Returns settings class of entities produced by this factory
     */
    val settings: Class<out IStateSettings>

    /**
     * Creates an entity with provided [settings]
     *
     * @param settings entity settings
     * @return entity instance
     */
    fun create(settings: IStateSettings?, channel: IChannel): IState
}
