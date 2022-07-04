package com.exactpro.th2.http.client.dirty.handler.state

import com.exactpro.th2.conn.dirty.tcp.core.api.IContext
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse

interface IStateManager {
    fun init(context: IContext<IProtocolHandlerSettings>)
    fun onRequest(request: FullHttpRequest) = Unit
    fun onResponse(request: FullHttpResponse) = Unit
}