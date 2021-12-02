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

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals

class TestCertificateValidation {
    @Test fun `validation enabled`() = HOSTS.forEach { host ->
        assertThrows<SSLHandshakeException>(host) { get(host, true) }
    }

    @Test fun `validation disabled`() = HOSTS.forEach { host ->
        assertEquals(200, get(host, false).statusCode, host)
    }

    companion object {
        private val HOSTS = listOf(
            "expired.badssl.com",
            "self-signed.badssl.com",
            "untrusted-root.badssl.com",
            // "revoked.badssl.com", // something is wrong with it - valid in Java/Chrome, but invalid in Firefox
        )
    }
}
