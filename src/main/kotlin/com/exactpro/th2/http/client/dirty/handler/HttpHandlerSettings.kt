package com.exactpro.th2.http.client.dirty.handler

import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings
import com.exactpro.th2.http.client.dirty.handler.state.DefaultStateFactory
import com.exactpro.th2.http.client.dirty.handler.state.IStateFactory
import com.exactpro.th2.http.client.dirty.handler.state.IStateSettings
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
}

class StateSettingsDeserializer<T : IStateSettings>() : JsonDeserializer<T>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): T = p.readValueAs(load<IStateFactory>(DefaultStateFactory::class.java).settings) as T
}