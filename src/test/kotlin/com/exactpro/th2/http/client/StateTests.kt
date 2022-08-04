/*
 * Copyright 2022-2022 Exactpro (Exactpro Systems Limited)
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

import com.exactpro.th2.http.client.dirty.handler.data.pointers.BodyPointer
import com.exactpro.th2.http.client.dirty.handler.data.pointers.HeadersPointer
import com.exactpro.th2.http.client.dirty.handler.data.pointers.MethodPointer
import com.exactpro.th2.http.client.dirty.handler.data.DirtyHttpRequest
import com.exactpro.th2.http.client.dirty.handler.data.pointers.StringPointer
import com.exactpro.th2.http.client.dirty.handler.data.pointers.VersionPointer
import com.exactpro.th2.http.client.dirty.handler.parsers.HeaderParser
import com.exactpro.th2.http.client.dirty.handler.parsers.StartLineParser
import com.exactpro.th2.http.client.dirty.handler.stateapi.DefaultState
import com.exactpro.th2.http.client.dirty.handler.stateapi.DefaultStateSettings
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.Base64

class StateTests {

    @Test
    fun `default authHeader test`() {
        val requestString = """
            GET /test HTTP/1.1${"\r"}
            Accept: Something${"\r"}
            Connection: close${"\r"}
            Host: w3schools.com${"\r"}
            
            name1=value1&name2=value2
        """.trimIndent()
        val buffer = Unpooled.buffer().writeBytes(requestString.toByteArray())

        val headerParser = HeaderParser()
        val lineParser = StartLineParser()
        check(lineParser.parseLine(buffer))
        check(headerParser.parseHeaders(buffer))
        val headers = headerParser.getHeaders()
        val container = HeadersPointer(requestString.indexOf("\n")+1, 17 + 17 + 19 + 3, Unpooled.buffer().writeBytes(requestString.toByteArray()), headers)

        val user = "test_user"
        val password = "test_password"
        val state = DefaultState(DefaultStateSettings(user, password))
        val request = DirtyHttpRequest(MethodPointer(0, HttpMethod.GET), StringPointer(5, "/test"), VersionPointer(11, HttpVersion.HTTP_1_1), BodyPointer(82, buffer, 25), container, buffer)
        state.onRequest(request)
        val auth = request.headers["Authorization"]
        Assertions.assertNotNull(auth)
        Base64.getDecoder().decode(auth!!.removePrefix("Basic ")).let { userAndPass ->
            val parts = userAndPass.decodeToString().split(":")
            Assertions.assertEquals(2, parts.size)
            Assertions.assertEquals(user, parts[0])
            Assertions.assertEquals(password, parts[1])
        }

    }
}