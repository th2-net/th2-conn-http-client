package com.exactpro.th2.http.client.dirty.handler

import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings

class HttpHandlerSettings: IProtocolHandlerSettings {
    var defaultHeaders: Map<String, String> = mapOf()
    var stateSettings: String? = null
}