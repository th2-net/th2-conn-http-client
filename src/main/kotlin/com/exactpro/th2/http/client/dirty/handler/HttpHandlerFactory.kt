package com.exactpro.th2.http.client.dirty.handler

import com.exactpro.th2.conn.dirty.tcp.core.api.IContext
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerFactory
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings
import com.exactpro.th2.http.client.dirty.handler.state.DefaultStateFactory
import com.exactpro.th2.http.client.dirty.handler.state.IStateFactory
import com.exactpro.th2.http.client.dirty.handler.state.IStateSettings
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import mu.KotlinLogging
import java.util.ServiceLoader

class HttpHandlerFactory: IProtocolHandlerFactory {
    override val name: String
        get() = HttpHandlerFactory::class.java.name
    override val settings: Class<out IProtocolHandlerSettings> = HttpHandlerSettings::class.java

    override fun create(context: IContext<IProtocolHandlerSettings>): HttpHandler {
        val stateFactory = load<IStateFactory>(DefaultStateFactory::class.java)
        LOGGER.info { "Loaded state factory: ${stateFactory.name}" }
        val module = SimpleModule().addAbstractTypeMapping(IStateSettings::class.java, stateFactory.settings)
        val mapper = JsonMapper.builder()
            .addModule(KotlinModule(nullIsSameAsDefault = true))
            .addModule(module)
            .build()

        val handlerSettings = context.settings as HttpHandlerSettings
        val stateSettings = handlerSettings.stateSettings?.run {
            mapper.readValue(this, stateFactory.settings)
        }

        return HttpHandler(context, stateFactory.create(stateSettings))
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

    companion object {
        private val LOGGER = KotlinLogging.logger { this::class.java.simpleName }
    }
}