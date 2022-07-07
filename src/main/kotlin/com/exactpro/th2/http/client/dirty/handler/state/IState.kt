package com.exactpro.th2.http.client.dirty.handler.state

import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse

interface IState: AutoCloseable {
    fun onOpen() = Unit
    fun onRequest(request: FullHttpRequest) = Unit
    fun onResponse(response: FullHttpResponse) = Unit
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
    fun create(settings: IStateSettings?): IState
}
