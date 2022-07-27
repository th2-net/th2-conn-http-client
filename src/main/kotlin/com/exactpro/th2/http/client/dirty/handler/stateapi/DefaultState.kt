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
import com.google.auto.service.AutoService
import java.util.Base64
import kotlin.text.Charsets.UTF_8

@AutoService(DefaultStateSettings::class)
class DefaultState(private val settings: DefaultStateSettings?) : IState {
    private val authHeader = settings?.run { "Basic ${Base64.getEncoder().encodeToString("${username}:${password}".toByteArray(UTF_8))}" } ?: ""
    override fun onRequest(request: DirtyHttpRequest) {
        if (settings != null) request.headers["Authorization"] = authHeader
    }
}

@AutoService(IStateSettings::class)
data class DefaultStateSettings(val username: String, val password: String): IStateSettings

@AutoService(IStateFactory::class)
class DefaultStateFactory : IStateFactory {
    override val name: String
        get() = DefaultStateFactory::class.java.simpleName
    override val settings: Class<DefaultStateSettings>
        get() = DefaultStateSettings::class.java
    override fun create(settings: IStateSettings?, channel: IChannel): IState = DefaultState(settings as? DefaultStateSettings)
}