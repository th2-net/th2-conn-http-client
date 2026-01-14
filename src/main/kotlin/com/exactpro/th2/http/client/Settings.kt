/*
 * Copyright 2026 Exactpro (Exactpro Systems Limited)
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

import com.exactpro.th2.http.client.api.IAuthSettings
import com.exactpro.th2.http.client.api.IAuthSettingsTypeProvider
import com.exactpro.th2.http.client.api.impl.AuthSettingsDeserializer
import com.exactpro.th2.http.client.api.impl.BasicAuthSettingsTypeProvider
import com.exactpro.th2.http.client.util.Certificate
import com.exactpro.th2.http.client.util.CertificateConverter
import com.exactpro.th2.http.client.util.PrivateKeyConverter
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.security.PrivateKey
import java.security.cert.X509Certificate

data class InternalSessionSettings(
    val host: String? = null,
    val https: Boolean? = null,
    val port: Int? = null,
    val readTimeout: Int? = null,
    val maxParallelRequests: Int? = null,
    val keepAliveTimeout: Long? = null,
    val defaultHeaders: Map<String, List<String>>? = null,
    val auth: IAuthSettings? = null,
    val validateCertificates: Boolean? = null,
    @param:JsonDeserialize(converter = CertificateConverter::class) val clientCertificate: X509Certificate? = null,
    @param:JsonDeserialize(converter = PrivateKeyConverter::class) val certificatePrivateKey: PrivateKey? = null,
    val publishSentEvents: Boolean? = null,
)

data class InternalSettings(
    val https: Boolean = false,
    val host: String? = null,
    val port: Int = if (https) 443 else 80,
    val readTimeout: Int = 5000,
    val maxParallelRequests: Int = 5,
    val keepAliveTimeout: Long = 15000,
    val defaultHeaders: Map<String, List<String>> = emptyMap(),
    val sessionAlias: String? = null,
    val auth: IAuthSettings? = null,
    val validateCertificates: Boolean = true,
    @param:JsonDeserialize(converter = CertificateConverter::class) val clientCertificate: X509Certificate? = null,
    @param:JsonDeserialize(converter = PrivateKeyConverter::class) val certificatePrivateKey: PrivateKey? = null,

    val useTransport: Boolean = false,
    @Deprecated("the parameter isn't used any more", level = DeprecationLevel.ERROR) val batcherThreads: Int = 2,
    val maxBatchSize: Int = 1000,
    val maxFlushTime: Long = 1000,
    val publishSentEvents: Boolean = true,
    val sessions: Map<String, InternalSessionSettings> = emptyMap(),
)

data class SessionSettings(
    val https: Boolean,
    val host: String,
    val port: Int,
    val readTimeout: Int,
    val maxParallelRequests: Int,
    val keepAliveTimeout: Long,
    val defaultHeaders: Map<String, List<String>>,
    val auth: IAuthSettings?,
    val validateCertificates: Boolean,
    val clientCertificate: X509Certificate?,
    val certificatePrivateKey: PrivateKey?,
    val publishSentEvents: Boolean,
) {
    val certificate: Certificate? = clientCertificate?.run {
        val key = requireNotNull(certificatePrivateKey) {
            "'${::clientCertificate.name}' setting requires '${::certificatePrivateKey.name}' setting to be set"
        }

        Certificate(this, key)
    }
}

data class Settings(
    val useTransport: Boolean,
    val maxBatchSize: Int,
    val maxFlushTime: Long,
    val sessions: Map<String, SessionSettings>,
)

fun getSettings(getFunc: (Class<InternalSettings>, ObjectMapper) -> InternalSettings): Settings {
    val authSettingsType = load<IAuthSettingsTypeProvider>(BasicAuthSettingsTypeProvider::class.java).type
    val mapper = JsonMapper.builder()
        .addModule(
            KotlinModule.Builder()
                .withReflectionCacheSize(512)
                .configure(KotlinFeature.NullToEmptyCollection, false)
                .configure(KotlinFeature.NullToEmptyMap, false)
                .configure(KotlinFeature.NullIsSameAsDefault, true)
                .configure(KotlinFeature.SingletonSupport, true)
                .configure(KotlinFeature.StrictNullChecks, false)
                .build()
        )
        .addModule(
            SimpleModule().addDeserializer(
                IAuthSettings::class.java,
                AuthSettingsDeserializer(authSettingsType)
            )
        )
        .build()
    val settings: InternalSettings = getFunc(InternalSettings::class.java, mapper)
    return Settings(
        useTransport = settings.useTransport,
        maxBatchSize = settings.maxBatchSize,
        maxFlushTime = settings.maxFlushTime,
        sessions = if (settings.sessions.isEmpty()) {
            val host = requireNotNull(settings.host) {
                "default 'host' option can't null when 'sessions' option isn't specified"
             }
            val alias = requireNotNull(settings.sessionAlias) {
                "default 'sessionAlias' option can't null when 'sessions' option isn't specified"
            }
            mapOf(alias to SessionSettings(
                https = settings.https,
                host = host,
                port = settings.port,
                readTimeout = settings.readTimeout,
                maxParallelRequests = settings.maxParallelRequests,
                keepAliveTimeout = settings.keepAliveTimeout,
                defaultHeaders = settings.defaultHeaders,
                auth = settings.auth,
                validateCertificates = settings.validateCertificates,
                clientCertificate = settings.clientCertificate,
                certificatePrivateKey = settings.certificatePrivateKey,
                publishSentEvents = settings.publishSentEvents,
            ))
        } else {
            settings.sessions.mapValues { (key, value) ->
                SessionSettings(
                    https = value.https ?: settings.https,
                    host = value.host ?: requireNotNull(settings.host) {
                        "default 'host' option can't null because '$key' session hasn't got 'host' option"
                    },
                    port = value.port ?: settings.port,
                    readTimeout = value.readTimeout ?: settings.readTimeout,
                    maxParallelRequests = value.maxParallelRequests ?: settings.maxParallelRequests,
                    keepAliveTimeout = value.keepAliveTimeout ?: settings.keepAliveTimeout,
                    defaultHeaders = value.defaultHeaders ?: settings.defaultHeaders,
                    auth = value.auth ?: settings.auth,
                    validateCertificates = value.validateCertificates ?: settings.validateCertificates,
                    clientCertificate = value.clientCertificate ?: settings.clientCertificate,
                    certificatePrivateKey = value.certificatePrivateKey ?: settings.certificatePrivateKey,
                    publishSentEvents = value.publishSentEvents ?: settings.publishSentEvents,
                )
            }
        }
    )
}