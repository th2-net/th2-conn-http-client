package com.exactpro.th2.http.client.dirty.handler

import com.exactpro.th2.conn.dirty.tcp.core.api.IContext
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerFactory
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings
import com.exactpro.th2.http.client.dirty.handler.state.DefaultStateManager
import com.exactpro.th2.http.client.dirty.handler.state.IStateManager
import java.util.ServiceLoader

class HttpHandlerFactory: IProtocolHandlerFactory {
    override val name: String
        get() = HttpHandlerFactory::class.java.name
    override val settings: Class<out IProtocolHandlerSettings> = HttpHandlerSettings::class.java
    override fun create(context: IContext<IProtocolHandlerSettings>): HttpHandler {
        val stateManager = load<IStateManager>(DefaultStateManager::class.java)
        return HttpHandler(context, stateManager)
    }

    private inline fun <reified T> load(defaultImpl: Class<out T>): T {
        val instances = ServiceLoader.load(T::class.java).toList()

        return when (instances.size) {
            0 -> error("No instances of ${T::class.simpleName}")
            1 -> instances.first()
            2 -> instances.first { !defaultImpl.isInstance(it) }
            else -> error("More than 1 non-default instance of ${T::class.simpleName} has been found: $instances")
        }
    }
}