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

package com.exactpro.th2.http.client.util

import com.fasterxml.jackson.databind.util.StdConverter
import java.io.File
import java.net.Socket
import java.security.KeyFactory
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

val INSECURE_TRUST_MANAGER = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

fun File.loadPrivateKey(): PrivateKey = useLines { lines ->
    lines.filterNot { it == "-----BEGIN PRIVATE KEY-----" || it == "-----END PRIVATE KEY-----" }
        .joinToString("")
        .run(Base64.getDecoder()::decode)
        .run(::PKCS8EncodedKeySpec)
        .run(KeyFactory.getInstance("RSA")::generatePrivate)
}

fun File.loadCertificate(): X509Certificate = inputStream().use {
    CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
}

data class Certificate(val certificate: X509Certificate, val privateKey: PrivateKey)

fun getSocketFactory(
    https: Boolean,
    validateCertificates: Boolean,
    clientCertificate: Certificate?
): SocketFactory {
    if (!https) {
        return SocketFactory.getDefault()
    }

    val keyManagers = clientCertificate?.run { arrayOf(SingleCertKeyManager(this)) }
    val trustManagers = if (validateCertificates) null else arrayOf(INSECURE_TRUST_MANAGER)

    return SSLContext.getInstance("TLS").run {
        init(keyManagers, trustManagers, null)
        socketFactory
    }
}

class SingleCertKeyManager(certificate: Certificate) : X509KeyManager {
    private val aliases = arrayOf("client")
    private val certificates = arrayOf(certificate.certificate)
    private val privateKey = certificate.privateKey
    override fun getClientAliases(keyType: String, issuers: Array<out Principal>?) = aliases
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "client"
    override fun getServerAliases(keyType: String, issuers: Array<out Principal>?) = null
    override fun chooseServerAlias(keyType: String, issuers: Array<out Principal>?, socket: Socket?) = null
    override fun getCertificateChain(alias: String) = certificates
    override fun getPrivateKey(alias: String): PrivateKey = privateKey
}

class CertificateConverter : StdConverter<String, X509Certificate>() {
    override fun convert(path: String): X509Certificate = path.runCatching {
        File(path).loadCertificate()
    }.getOrElse {
        throw IllegalArgumentException("Failed to load X.509 certificate from path: $path", it)
    }
}

class PrivateKeyConverter : StdConverter<String, PrivateKey>() {
    override fun convert(path: String): PrivateKey = path.runCatching {
        File(path).loadPrivateKey()
    }.getOrElse {
        throw IllegalArgumentException("Failed to load RSA private key from path: $path", it)
    }
}


