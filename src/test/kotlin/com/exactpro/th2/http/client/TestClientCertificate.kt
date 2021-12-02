/*
 * Copyright 2021-2021 Exactpro (Exactpro Systems Limited)
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

import com.exactpro.th2.http.client.util.Certificate
import com.exactpro.th2.http.client.util.loadCertificate
import com.exactpro.th2.http.client.util.loadPrivateKey
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.text.Charsets.UTF_8

class TestClientCertificate {
    private val certificate = Certificate(
        File("src/test/resources/test.crt").loadCertificate(),
        File("src/test/resources/test.key").loadPrivateKey()
    )

    @Test fun `with certificate`() {
        val response = get("certauth.idrix.fr", clientCertificate = certificate)
        assertEquals(200, response.statusCode)
        assertTrue(response.body.isPresent)
        assertTrue("TLSv1.2 Authentication OK!" in response.body.get().decodeBodyToString(UTF_8))
    }

    @Test fun `without certificate`() {
        val response = get("certauth.idrix.fr")
        println(response)
        assertEquals(200, response.statusCode)
        assertTrue(response.body.isPresent)
        assertTrue("No TLS client certificate presented" in response.body.get().decodeBodyToString(UTF_8))
    }
}