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

package com.exactpro.th2.http.client.dirty.handler

import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings
import com.exactpro.th2.http.client.dirty.handler.stateapi.DefaultStateFactory
import com.exactpro.th2.http.client.dirty.handler.stateapi.IStateFactory
import com.exactpro.th2.http.client.dirty.handler.stateapi.IStateSettings
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.google.auto.service.AutoService

@AutoService(IProtocolHandlerSettings::class)
class HttpHandlerSettings: IProtocolHandlerSettings {
    var defaultHeaders: Map<String, List<String>> = mapOf()

    @JsonDeserialize(using = StateSettingsDeserializer::class)
    var stateSettings: IStateSettings? = null

    var validation = false
}

class StateSettingsDeserializer<T : IStateSettings>() : JsonDeserializer<T>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): T = p.readValueAs(load<IStateFactory>(DefaultStateFactory::class.java).settings) as T
}