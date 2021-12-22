package com.exactpro.th2.http.client

import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.http.client.api.Th2RawHttpRequest
import com.exactpro.th2.http.client.util.toRawMessage
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RequestLine
import java.net.URI

class CommonTests {

    @Test
    fun `parent event id loss test`() {
        val parentEventID = "testParentId"
        val metadata = mapOf( "propertyOne" to "propertyOneValue", "propertyTwo" to "propertyTwoValue")
        val requestLine =  RequestLine("GET", URI("/test"), HttpVersion.HTTP_1_1)
        val request = Th2RawHttpRequest(requestLine, RawHttpHeaders.CONTENT_LENGTH_ZERO, null, null, parentEventID, metadata)

        val rawMessage = request.toRawMessage(ConnectionID.getDefaultInstance(), 12345L)
        val newMetadata = rawMessage.metadata.propertiesMap
        Assertions.assertEquals(metadata.values.size + 2 /* method and uri */, newMetadata.values.size)
        metadata.forEach {
            Assertions.assertTrue(newMetadata.containsKey(it.key))
            Assertions.assertEquals(it.value, newMetadata[it.key])
        }
        Assertions.assertEquals(parentEventID, rawMessage.parentEventId.id)
    }
}