package com.mytv.cn.network

import com.mytv.cn.data.InteractionSecurity
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPublicKeySpec
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object InteractionSigner {
    private val provider = BouncyCastleProvider()

    fun parse(rawPayload: String, privateKey: String): InteractionSecurity {
        val found = LinkedHashMap<String, String>()
        runCatching { visit(JSONObject(rawPayload), found) }
        if (found.isEmpty()) parseKeyValueText(rawPayload, found)
        return InteractionSecurity(
            rawPayload = rawPayload,
            ticket = found.firstValue("ticket", "bd_ticket_guard_ticket"),
            tsSign = found.firstValue("ts_sign", "tsSign", "bd_ticket_guard_ts_sign"),
            privateKey = privateKey.ifBlank { found.firstValue("private_key", "privateKey", "ec_privateKey") },
            serverCertificate = found.firstValue("server_cert", "serverCert", "server_certificate", "cert")
        )
    }

    fun ticketGuardParameters(path: String, security: InteractionSecurity): Map<String, String> {
        if (!security.usable) return emptyMap()
        val private = readPrivateKey(security.privateKey)
        val public = publicKey(private)
        return linkedMapOf(
            "bd-ticket-guard-client-data" to clientData(path, security, private),
            "bd-ticket-guard-ree-public-key" to Base64.getEncoder().encodeToString(public.encoded),
            "bd-ticket-guard-version" to "2",
            "bd-ticket-guard-web-sign-type" to "1",
            "bd-ticket-guard-web-version" to "2"
        )
    }

    /** x-secsdk-web-signature observed in myDV 2.1.37's user-center request chain. */
    fun userCenterSignature(params: Map<String, String>, uifid: String, timestamp: String): String {
        val all = LinkedHashMap(params).apply {
            put("uifid", uifid)
            put("timestamp", timestamp)
        }
        val source = "${uifid}_${timestamp}_A96D855A08C0A9707F8BEF0D9A527E4E_" +
            all.entries.joinToString("&") { "${it.key}=${it.value}" }
        return MessageDigest.getInstance("MD5").digest(source.toByteArray()).toHex()
    }

    private fun clientData(path: String, security: InteractionSecurity, private: PrivateKey): String {
        val timestamp = System.currentTimeMillis() / 1000
        val content = "ticket=${security.ticket}&path=$path&timestamp=$timestamp"
        val signature = if (security.serverCertificate.isNotBlank()) {
            runCatching { hmacWithServerCertificate(content, private, security.serverCertificate) }
                .getOrElse { ecdsa(content, private) }
        } else {
            ecdsa(content, private)
        }
        val payload = JSONObject()
            .put("ts_sign", security.tsSign)
            .put("req_content", "ticket,path,timestamp")
            .put("req_sign", signature)
            .put("timestamp", timestamp)
            .put("t_trust", 1)
            .toString()
        return Base64.getEncoder().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    }

    private fun ecdsa(content: String, private: PrivateKey): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(private)
        signer.update(content.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    private fun hmacWithServerCertificate(content: String, private: PrivateKey, certificate: String): String {
        val certText = certificate.trim().let { value ->
            if (value.contains("BEGIN CERTIFICATE")) value else {
                val clean = value.replace("\\s".toRegex(), "")
                "-----BEGIN CERTIFICATE-----\n" + clean.chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----"
            }
        }
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val serverPublic = certificateFactory.generateCertificate(
            ByteArrayInputStream(certText.toByteArray(StandardCharsets.UTF_8))
        ).publicKey
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(private)
        agreement.doPhase(serverPublic, true)
        val secret = agreement.generateSecret().let { source ->
            ByteArray(32).also { out ->
                val count = minOf(32, source.size)
                source.copyInto(out, 32 - count, source.size - count, source.size)
            }
        }
        val extract = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        }.doFinal(secret)
        val key = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(extract, "HmacSHA256"))
            doFinal(byteArrayOf(1)).copyOf(32)
        }
        val result = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(content.toByteArray(StandardCharsets.UTF_8))
        }
        return Base64.getEncoder().encodeToString(result)
    }

    private fun readPrivateKey(value: String): PrivateKey {
        val trimmed = value.trim()
        if (trimmed.startsWith("-----BEGIN")) {
            PEMParser(StringReader(trimmed)).use { parser ->
                return when (val parsed = parser.readObject()) {
                    is PEMKeyPair -> JcaPEMKeyConverter().setProvider(provider).getKeyPair(parsed).private
                    is PrivateKeyInfo -> JcaPEMKeyConverter().setProvider(provider).getPrivateKey(parsed)
                    else -> error("不支持的 PEM 私钥格式")
                }
            }
        }
        val clean = trimmed.replace("\\s".toRegex(), "")
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(clean)))
    }

    private fun publicKey(private: PrivateKey): PublicKey {
        val ec = private as? ECPrivateKey ?: error("互动私钥不是 EC 私钥")
        val parameters = ECNamedCurveTable.getParameterSpec("secp256r1")
        val point = parameters.g.multiply(ec.s).normalize()
        return KeyFactory.getInstance("EC", provider).generatePublic(ECPublicKeySpec(point, parameters))
    }

    private fun visit(value: Any?, out: MutableMap<String, String>) {
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                val child = value.opt(key)
                if (child is String && child.isNotBlank()) {
                    out.putIfAbsent(key, child)
                    if (child.trimStart().startsWith('{')) runCatching { visit(JSONObject(child), out) }
                } else visit(child, out)
            }
            is JSONArray -> repeat(value.length()) { visit(value.opt(it), out) }
        }
    }

    private fun parseKeyValueText(text: String, out: MutableMap<String, String>) {
        text.split('\n', ';', '&').forEach { line ->
            val index = line.indexOf('=')
            if (index > 0) out[line.substring(0, index).trim()] = line.substring(index + 1).trim()
        }
    }

    private fun Map<String, String>.firstValue(vararg keys: String): String =
        keys.firstNotNullOfOrNull { this[it]?.takeIf(String::isNotBlank) }.orEmpty()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
