package com.exactpro.th2.http.client.dirty.handler.state

import com.google.auto.service.AutoService
import io.netty.handler.codec.http.FullHttpRequest
import java.util.Base64
import kotlin.text.Charsets.UTF_8

@AutoService(DefaultStateSettings::class)
class DefaultState(private val settings: DefaultStateSettings?) : IState {
    private val authHeader = settings?.run { "Basic ${Base64.getEncoder().encodeToString("${username}:${password}".toByteArray(UTF_8))}" } ?: ""
    override fun onRequest(request: FullHttpRequest) {
        if (settings != null) request.headers()["Authorization"] = authHeader
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
    override fun create(settings: IStateSettings?): IState = DefaultState(settings as? DefaultStateSettings)
}