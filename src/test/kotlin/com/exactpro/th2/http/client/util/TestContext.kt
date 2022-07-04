package com.exactpro.th2.http.client.util

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.schema.dictionary.DictionaryType
import com.exactpro.th2.conn.dirty.tcp.core.api.IChannel
import com.exactpro.th2.conn.dirty.tcp.core.api.IContext
import com.exactpro.th2.conn.dirty.tcp.core.api.IProtocolHandlerSettings
import java.io.InputStream

class TestContext(override val settings: IProtocolHandlerSettings): IContext<IProtocolHandlerSettings> {
    override lateinit var channel: IChannel

    fun init(channel: IChannel) {
        this.channel = channel
    }

    override fun get(dictionary: DictionaryType): InputStream {
        error("Not yet implemented")
    }

    override fun send(event: Event) {
        error("Not yet implemented")
    }

}