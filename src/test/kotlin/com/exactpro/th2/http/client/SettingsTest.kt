/*
 * Copyright 2025 Exactpro (Exactpro Systems Limited)
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

import com.exactpro.th2.http.client.api.impl.BasicAuthSettings
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNotEmpty
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import strikt.assertions.isTrue

class SettingsTest {

    @Test
    fun `get settings`() {
        val settings = getSettings { _, mapper -> mapper.readValue("""
            {
                "https": true,
                "host": "jsonplaceholder.typicode.com",
                "port": 444,
                "readTimeout": 5555,
                "maxParallelRequests": 10,
                "keepAliveTimeout": 15150,
                "defaultHeaders": {
                    "Content-Type": ["application/json"]
                },
                "sessionAlias": "client-http-1",
                "auth": {
                    "username": "test-username", 
                    "password": "test-password"
                },
                "validateCertificates": false,
                "useTransport": true,
                "maxBatchSize": 1111,
                "maxFlushTime": 2222,
                "clientCertificate": "src/test/resources/test.crt",
                "certificatePrivateKey": "src/test/resources/test.key"
            }
        """.trimIndent()) }

        expectThat(settings) {
            get { useTransport }.isTrue()
            get { maxBatchSize }.isEqualTo(1111)
            get { maxFlushTime }.isEqualTo(2222)
            // options after extension
            get { sessions }.isNotEmpty() and {
                hasSize(1)
                get { get("client-http-1") }.isNotNull() and {
                    get { https }.isTrue()
                    get { host }.isEqualTo("jsonplaceholder.typicode.com")
                    get { port }.isEqualTo(444)
                    get { readTimeout }.isEqualTo(5555)
                    get { maxParallelRequests }.isEqualTo(10)
                    get { keepAliveTimeout }.isEqualTo(15150)
                    get { defaultHeaders }.isNotEmpty() and {
                        hasSize(1)
                        get { get("Content-Type") }.isEqualTo(listOf("application/json"))
                    }
                    get { auth }.isA<BasicAuthSettings>() and {
                        get { username }.isEqualTo("test-username")
                        get { password }.isEqualTo("test-password")
                    }
                    get { validateCertificates }.isFalse()
                    get { clientCertificate }.isNotNull()
                    get { certificatePrivateKey }.isNotNull()
                    get { certificate }.isNotNull()
                }
            }
        }
    }

    @Test
    fun `get settings (empty)`() {
        val settings = getSettings { _, mapper -> mapper.readValue("""
            {
                "host": "jsonplaceholder.typicode.com",
                "sessionAlias": "client-http-1"
            }
        """.trimIndent()) }

        expectThat(settings) {
            get { useTransport }.isFalse()
            get { maxBatchSize }.isEqualTo(1000)
            get { maxFlushTime }.isEqualTo(1000)
            // options after extension
            get { sessions }.isNotEmpty() and {
                hasSize(1)
                get { get("client-http-1") }.isNotNull() and {
                    get { https }.isFalse()
                    get { host }.isEqualTo("jsonplaceholder.typicode.com")
                    get { port }.isEqualTo(80)
                    get { readTimeout }.isEqualTo(5000)
                    get { maxParallelRequests }.isEqualTo(5)
                    get { keepAliveTimeout }.isEqualTo(15000)
                    get { defaultHeaders }.isEmpty()
                    get { auth }.isNull()
                    get { validateCertificates }.isTrue()
                    get { clientCertificate }.isNull()
                    get { certificatePrivateKey }.isNull()
                    get { certificate }.isNull()
                }
            }
        }
    }

    @Test
    fun `get multiple settings (default)`() {
        val settings = getSettings { _, mapper -> mapper.readValue("""
            {
                "https": true,
                "host": "jsonplaceholder.typicode.com",
                "port": 444,
                "readTimeout": 5555,
                "maxParallelRequests": 10,
                "keepAliveTimeout": 15150,
                "defaultHeaders": {
                    "Content-Type": ["application/json"]
                },
                "auth": {
                    "username": "test-username", 
                    "password": "test-password"
                },
                "validateCertificates": false,
                "useTransport": true,
                "maxBatchSize": 1111,
                "maxFlushTime": 2222,
                "clientCertificate": "src/test/resources/test.crt",
                "certificatePrivateKey": "src/test/resources/test.key",
                "sessions": {
                    "client-http-1": {},
                    "client-http-2": {}
                }
            }
        """.trimIndent()) }

        expectThat(settings) {
            get { useTransport }.isTrue()
            get { maxBatchSize }.isEqualTo(1111)
            get { maxFlushTime }.isEqualTo(2222)
            // options after extension
            get { sessions }.isNotEmpty() and {
                hasSize(2)
                get { get("client-http-1") }.isNotNull() and {
                    get { https }.isTrue()
                    get { host }.isEqualTo("jsonplaceholder.typicode.com")
                    get { port }.isEqualTo(444)
                    get { readTimeout }.isEqualTo(5555)
                    get { maxParallelRequests }.isEqualTo(10)
                    get { keepAliveTimeout }.isEqualTo(15150)
                    get { defaultHeaders }.isNotEmpty() and {
                        hasSize(1)
                        get { get("Content-Type") }.isEqualTo(listOf("application/json"))
                    }
                    get { auth }.isA<BasicAuthSettings>() and {
                        get { username }.isEqualTo("test-username")
                        get { password }.isEqualTo("test-password")
                    }
                    get { validateCertificates }.isFalse()
                    get { clientCertificate }.isNotNull()
                    get { certificatePrivateKey }.isNotNull()
                    get { certificate }.isNotNull()
                }
                get { get("client-http-2") }.isNotNull() and {
                    get { https }.isTrue()
                    get { host }.isEqualTo("jsonplaceholder.typicode.com")
                    get { port }.isEqualTo(444)
                    get { readTimeout }.isEqualTo(5555)
                    get { maxParallelRequests }.isEqualTo(10)
                    get { keepAliveTimeout }.isEqualTo(15150)
                    get { defaultHeaders }.isNotEmpty() and {
                        hasSize(1)
                        get { get("Content-Type") }.isEqualTo(listOf("application/json"))
                    }
                    get { auth }.isA<BasicAuthSettings>() and {
                        get { username }.isEqualTo("test-username")
                        get { password }.isEqualTo("test-password")
                    }
                    get { validateCertificates }.isFalse()
                    get { clientCertificate }.isNotNull()
                    get { certificatePrivateKey }.isNotNull()
                    get { certificate }.isNotNull()
                }
            }
        }
    }

    @Test
    fun `get multiple settings (empty)`() {
        val settings = getSettings { _, mapper -> mapper.readValue("""
            {
                "host": "jsonplaceholder.typicode.com",
                "sessions": {
                    "client-http-1": {}
                }
            }
        """.trimIndent()) }

        expectThat(settings) {
            get { useTransport }.isFalse()
            get { maxBatchSize }.isEqualTo(1000)
            get { maxFlushTime }.isEqualTo(1000)
            // options after extension
            get { sessions }.isNotEmpty() and {
                hasSize(1)
                get { get("client-http-1") }.isNotNull() and {
                    get { https }.isFalse()
                    get { host }.isEqualTo("jsonplaceholder.typicode.com")
                    get { port }.isEqualTo(80)
                    get { readTimeout }.isEqualTo(5000)
                    get { maxParallelRequests }.isEqualTo(5)
                    get { keepAliveTimeout }.isEqualTo(15000)
                    get { defaultHeaders }.isEmpty()
                    get { auth }.isNull()
                    get { validateCertificates }.isTrue()
                    get { clientCertificate }.isNull()
                    get { certificatePrivateKey }.isNull()
                    get { certificate }.isNull()
                }
            }
        }
    }

    @Test
    fun `get multiple settings`() {
        val settings = getSettings { _, mapper -> mapper.readValue("""
            {
                "useTransport": true,
                "maxBatchSize": 1111,
                "maxFlushTime": 2222,
                "sessions": {
                    "client-http-1": {
                        "https": true,
                        "host": "test-host-1",
                        "port": 11,
                        "readTimeout": 12,
                        "maxParallelRequests": 13,
                        "keepAliveTimeout": 14,
                        "defaultHeaders": {
                            "Content-Type": ["application/json"]
                        },
                        "auth": {
                            "username": "test-username-1", 
                            "password": "test-password-1"
                        },
                        "validateCertificates": true,
                        "clientCertificate": "src/test/resources/test.crt",
                        "certificatePrivateKey": "src/test/resources/test.key"
                    },
                    "client-http-2": {
                        "https": false,
                        "host": "test-host-2",
                        "port": 21,
                        "readTimeout": 22,
                        "maxParallelRequests": 23,
                        "keepAliveTimeout": 24,
                        "defaultHeaders": {
                            "Content-Type": ["text"]
                        },
                        "auth": {
                            "username": "test-username-2", 
                            "password": "test-password-2"
                        },
                        "validateCertificates": false
                    }
                }
            }
        """.trimIndent()) }

        expectThat(settings) {
            get { useTransport }.isTrue()
            get { maxBatchSize }.isEqualTo(1111)
            get { maxFlushTime }.isEqualTo(2222)
            // options after extension
            get { sessions }.isNotEmpty() and {
                hasSize(2)
                get { get("client-http-1") }.isNotNull() and {
                    get { https }.isTrue()
                    get { host }.isEqualTo("test-host-1")
                    get { port }.isEqualTo(11)
                    get { readTimeout }.isEqualTo(12)
                    get { maxParallelRequests }.isEqualTo(13)
                    get { keepAliveTimeout }.isEqualTo(14)
                    get { defaultHeaders }.isNotEmpty() and {
                        hasSize(1)
                        get { get("Content-Type") }.isEqualTo(listOf("application/json"))
                    }
                    get { auth }.isA<BasicAuthSettings>() and {
                        get { username }.isEqualTo("test-username-1")
                        get { password }.isEqualTo("test-password-1")
                    }
                    get { validateCertificates }.isTrue()
                    get { clientCertificate }.isNotNull()
                    get { certificatePrivateKey }.isNotNull()
                    get { certificate }.isNotNull()
                }
                get { get("client-http-2") }.isNotNull() and {
                    get { https }.isFalse()
                    get { host }.isEqualTo("test-host-2")
                    get { port }.isEqualTo(21)
                    get { readTimeout }.isEqualTo(22)
                    get { maxParallelRequests }.isEqualTo(23)
                    get { keepAliveTimeout }.isEqualTo(24)
                    get { defaultHeaders }.isNotEmpty() and {
                        hasSize(1)
                        get { get("Content-Type") }.isEqualTo(listOf("text"))
                    }
                    get { auth }.isA<BasicAuthSettings>() and {
                        get { username }.isEqualTo("test-username-2")
                        get { password }.isEqualTo("test-password-2")
                    }
                    get { validateCertificates }.isFalse()
                    get { clientCertificate }.isNull()
                    get { certificatePrivateKey }.isNull()
                    get { certificate }.isNull()
                }
            }
        }
    }
}