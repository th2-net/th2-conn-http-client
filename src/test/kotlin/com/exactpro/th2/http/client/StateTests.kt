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