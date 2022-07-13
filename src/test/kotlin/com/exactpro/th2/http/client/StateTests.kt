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

import com.exactpro.th2.http.client.dirty.handler.state.DefaultState
import com.exactpro.th2.http.client.dirty.handler.state.DefaultStateSettings
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.Base64

class StateTests {

    @Test
    fun `default authHeader test`() {
        val user = "test_user"
        val password = "test_password"
        val state = DefaultState(DefaultStateSettings(user, password))
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test")
        state.onRequest(request)
        request.headers().let { headers ->
            Assertions.assertTrue(headers.contains("Authorization"))
            Base64.getDecoder().decode(headers["Authorization"].removePrefix("Basic ")).let { userAndPass ->
                val parts = userAndPass.decodeToString().split(":")
                Assertions.assertEquals(2, parts.size)
                Assertions.assertEquals(user, parts[0])
                Assertions.assertEquals(password, parts[1])
            }
        }

    }
}