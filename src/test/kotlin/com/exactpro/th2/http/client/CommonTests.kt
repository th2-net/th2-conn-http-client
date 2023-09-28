/*
 * Copyright 2021-2023 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exactpro.th2.http.client

import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.http.client.api.decorators.Th2RawHttpRequest
import com.exactpro.th2.http.client.util.toProtoMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RequestLine
import java.net.URI
import kotlin.test.assertContains

class CommonTests {

    @Test
    fun `parent event id loss test`() {
        val parentEventID = EventID.newBuilder().setId("testParentId").build()
        val metadata = mapOf("propertyOne" to "propertyOneValue", "propertyTwo" to "propertyTwoValue")
        val requestLine = RequestLine("GET", URI("/test"), HttpVersion.HTTP_1_1)
        val request =
            Th2RawHttpRequest(requestLine, RawHttpHeaders.CONTENT_LENGTH_ZERO, null, null, parentEventID, metadata)

        val rawMessage = request.toProtoMessage(ConnectionID.getDefaultInstance(), 12345L)
        val newMetadata = rawMessage.metadata.propertiesMap
        assertEquals(metadata.values.size + 2 /* method and uri */ + 1 /* th2-request-id */, newMetadata.values.size)
        assertContains(newMetadata, "th2-request-id")
        assertFalse(newMetadata["th2-request-id"]?.isBlank() ?: true)
        metadata.forEach { (name, value) ->
            assertContains(newMetadata, name)
            assertEquals(value, newMetadata[name])
        }
        assertEquals(parentEventID, rawMessage.parentEventId)
    }
}