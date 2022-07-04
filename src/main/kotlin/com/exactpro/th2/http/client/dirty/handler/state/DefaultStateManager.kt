package com.exactpro.th2.http.client.dirty.handler.state

import com.exactpro.th2.conn.dirty.tcp.core.api.IContext
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings
import com.exactpro.th2.http.client.dirty.handler.HttpHandlerSettings

class DefaultStateManager: IStateManager {
    private lateinit var settings: HttpHandlerSettings
    override fun init(context: IContext<IProtocolHandlerSettings>) {
        settings = context.settings as HttpHandlerSettings
    }
}