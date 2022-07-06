package com.exactpro.th2.http.client.dirty.handler

import com.exactpro.th2.conn.dirty.tcp.core.api.IContext
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerFactory
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings
import com.exactpro.th2.http.client.dirty.handler.state.DefaultStateFactory
import com.exactpro.th2.http.client.dirty.handler.state.IStateFactory
import mu.KotlinLogging

class HttpHandlerFactory: IProtocolHandlerFactory {

    private val stateFactory = load<IStateFactory>(DefaultStateFactory::class.java).also {
        LOGGER.info { "Loaded state factory: ${it.name}" }
    }

    override val name: String
        get() = HttpHandlerFactory::class.java.name
    override val settings: Class<out IProtocolHandlerSettings> = HttpHandlerSettings::class.java

    override fun create(context: IContext<IProtocolHandlerSettings>): HttpHandler = (context.settings as HttpHandlerSettings).let { settings ->
        HttpHandler(context, stateFactory.create(settings.stateSettings), settings)
    }


    companion object {
        private val LOGGER = KotlinLogging.logger { this::class.java.simpleName }
    }
}

