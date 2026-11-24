package com.mytv.cn.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.mytv.cn.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit


enum class QrLoginStatus { IDLE, STARTING, WAITING_SCAN, SCANNED, EXPIRED, SUCCESS, FAILED }

data class QrLoginState(
    val status: QrLoginStatus = QrLoginStatus.IDLE,
    val message: String = "",
    val qrBitmap: Bitmap? = null
)

internal const val GET_QR_URL = "https://login.douyin.com/passport/web/get_qrcode/"
internal const val CHECK_QR_URL = "https://login.douyin.com/passport/web/check_qrconnect/"

/** Passport QR flow reconstructed from myDV 2.1.37's QrLoginManager. */
class QrLoginManager(
    private val settings: SettingsStore,
    private val signer: ABogusSigner
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun login(onState: (QrLoginState) -> Unit): String = withContext(Dispatchers.IO) {
        val cookies = LinkedHashMap<String, String>()
        try {
            onState(QrLoginState(QrLoginStatus.STARTING, "正在初始化登录会话"))
            execute(
                Request.Builder().url("https://www.douyin.com/").get()
                    .headers(homeHeaders()).build(),
                cookies
            )
            challenge(cookies)
            ttwidCheck(cookies)
            val verifyFp = verifyFp()
            val qrData = getQrCode(cookies, verifyFp)
            val token = qrData.optString("token")
            val bitmap = decodeQr(qrData.optString("qrcode"))
                ?: error("二维码解码失败")
            check(token.isNotBlank()) { "二维码响应缺少 token" }
            onState(QrLoginState(QrLoginStatus.WAITING_SCAN, "请使用抖音 App 扫码登录", bitmap))

            repeat(90) {
                val result = checkQr(cookies, token, verifyFp)
                val status = result.optString("status").ifBlank {
                    result.optString("scan_status").ifBlank { result.optInt("status_code", -1).toString() }
                }.lowercase()
                when {
                    status in setOf("1", "scanned", "scan", "waiting_confirm") ->
                        onState(QrLoginState(QrLoginStatus.SCANNED, "已扫码，请在手机端确认", bitmap))
                    status in setOf("2", "confirmed", "success", "3") || hasSession(cookies) -> {
                        result.optString("redirect_url").takeIf(String::isNotBlank)?.let { followRedirect(it, cookies) }
                        if (!hasSession(cookies)) result.optString("redirectUrl").takeIf(String::isNotBlank)?.let { followRedirect(it, cookies) }
                        check(hasSession(cookies)) { "登录确认成功，但未收到 sessionid" }
                        val header = cookieHeader(cookies)
                        settings.setCookie(header)
                        onState(QrLoginState(QrLoginStatus.SUCCESS, "登录成功", bitmap))
                        return@withContext header
                    }
                    status in setOf("4", "5", "expired", "timeout") -> {
                        onState(QrLoginState(QrLoginStatus.EXPIRED, "二维码已过期", bitmap))
                        error("二维码已过期")
                    }
                }
                delay(2_000)
            }
            onState(QrLoginState(QrLoginStatus.EXPIRED, "二维码已过期", bitmap))
            error("二维码已过期")
        } catch (error: Throwable) {
            if (error.message != "二维码已过期") {
                onState(QrLoginState(QrLoginStatus.FAILED, error.message ?: "扫码登录失败"))
            }
            throw error
        }
    }

    private fun challenge(cookies: LinkedHashMap<String, String>) {
        val now = System.currentTimeMillis()
        val params = linkedMapOf(
            "passport_jssdk_version" to "3.1.3",
            "passport_jssdk_type" to "normal",
            "is_from_ttaccountsdk" to "1",
            "aid" to "6383",
            "language" to "zh",
            "account_app_language" to "zh-CN",
            "ts" to (now / 1000).toString(),
            "request_host" to "https://www.douyin.com",
            "skip_c" to "1",
            "p_ca" to "4.0.17",
            "account_sdk_source" to "web",
            "p_js_v" to "3.1.3",
            "p_js_t" to "pro",
            "p_zt" to "3.3.10",
            "p_ver" to "1.1.3",
            "p_bd" to "1.0.1.19-fix.01",
            "p_ts" to now.toString(),
            "device_platform" to "web_app",
            "msToken" to randomToken(128)
        )
        val request = Request.Builder()
            .url("https://login.douyin.com/passport/web/challenge/?${query(params)}")
            .headers(passportHeaders("https://www.douyin.com/", cookies))
            .post(FormBody.Builder().build())
            .build()
        execute(request, cookies)
    }

    private fun ttwidCheck(cookies: LinkedHashMap<String, String>) {
        val body = JSONObject()
            .put("aid", 6383)
            .put("service", "www.douyin.com")
            .put("union", false)
            .put("unionHost", "")
            .put("needFid", false)
            .put("fid", "")
            .put("migrate_priority", 0)
            .toString()
        val request = Request.Builder()
            .url("https://login.douyin.com/ttwid/check/")
            .headers(passportHeaders("https://www.douyin.com/", cookies))
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        execute(request, cookies)
    }

    private suspend fun getQrCode(cookies: LinkedHashMap<String, String>, verifyFp: String): JSONObject {
        val params = passportParams(null, verifyFp, true)
        val rawQuery = query(params)
        val aBogus = signer.aBogus(rawQuery, "", GET_QR_URL)
        check(aBogus.isNotBlank()) { "登录签名失败" }
        val headers = passportHeaders("https://www.douyin.com/", cookies).newBuilder()
            .set("x-tt-passport-csrf-token", cookies["passport_csrf_token"].orEmpty())
            .build()
        val request = Request.Builder()
            .url("$GET_QR_URL?$rawQuery&a_bogus=${encode(aBogus)}")
            .headers(headers)
            .get()
            .build()
        return responseData(execute(request, cookies))
    }

    private suspend fun checkQr(
        cookies: LinkedHashMap<String, String>,
        token: String,
        verifyFp: String
    ): JSONObject {
        val params = passportParams(token, verifyFp, false)
        val rawQuery = query(params)
        val body = "token=${encode(token)}"
        val aBogus = signer.aBogus(rawQuery, "", CHECK_QR_URL)
        check(aBogus.isNotBlank()) { "登录轮询签名失败" }
        val headers = passportHeaders("https://www.douyin.com/", cookies).newBuilder()
            .set("Content-Type", "application/x-www-form-urlencoded")
            .set("x-tt-passport-csrf-token", cookies["passport_csrf_token"].orEmpty())
            .set("x-tt-passport-tte", "V2")
            .set("x-secsdk-csrf-token", cookies["__ac_nonce"].orEmpty())
            .build()
        val request = Request.Builder()
            .url("$CHECK_QR_URL?$rawQuery&a_bogus=${encode(aBogus)}")
            .headers(headers)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        return responseData(execute(request, cookies))
    }

    private fun passportParams(token: String?, verifyFp: String, next: Boolean): LinkedHashMap<String, String> {
        val now = System.currentTimeMillis()
        return linkedMapOf(
            "passport_jssdk_version" to "3.1.3",
            "cookie_enabled" to "true",
            "passport_jssdk_type" to "normal",
            "is_from_ttaccountsdk" to "1",
            "aid" to "6383",
            "browser_language" to "zh-CN",
            "browser_platform" to "Win32",
            "browser_name" to "Chrome",
            "browser_version" to "142.0.0.0",
            "language" to "zh",
            "account_app_language" to "zh-CN",
            "host" to "https://www.douyin.com",
            "passport_ztsdk" to "3.1.3",
            "app_id" to "6383",
            "mix_mode" to "1",
            "need_logo" to "false",
            "is_new_login" to "1",
            "is_from_iesaccountsaas" to "1",
            "p_ui" to "2.1.4",
            "p_ca" to "4.0.17",
            "p_ca_real" to "1.0.0.729",
            "account_sdk_source" to "web",
            "service" to "https://www.douyin.com",
            "p_js_v" to "3.1.3",
            "p_js_t" to "pro",
            "p_zt" to "3.3.10",
            "p_ver" to "1.1.3",
            "p_ver_real" to "0",
            "request_host" to "https://www.douyin.com",
            "p_bd" to "1.0.1.19-fix.01",
            "p_ts" to now.toString(),
            "p_no" to (now / 1000).toString(),
            "biz_trace_id" to now.toString(),
            "device_platform" to "web_app",
            "verifyFp" to verifyFp,
            "fp" to verifyFp,
            "msToken" to randomToken(128)
        ).apply {
            if (next) put("next", "https://www.douyin.com/")
            token?.takeIf(String::isNotBlank)?.let { put("token", it) }
        }
    }

    private fun followRedirect(url: String, cookies: LinkedHashMap<String, String>) {
        val target = if (url.startsWith("http")) url else "https://www.douyin.com$url"
        execute(
            Request.Builder().url(target).headers(passportHeaders("https://www.douyin.com/", cookies)).get().build(),
            cookies
        )
    }

    private fun execute(request: Request, cookies: LinkedHashMap<String, String>): String {
        client.newCall(request).execute().use { response ->
            response.headers("Set-Cookie").forEach { mergeCookie(cookies, it) }
            val body = response.body?.string().orEmpty()
            check(response.code in 200..399) { "登录接口 HTTP ${response.code}" }
            return body
        }
    }

    private fun responseData(body: String): JSONObject {
        val root = JSONObject(body)
        val message = root.optString("message")
        val data = root.optJSONObject("data") ?: JSONObject()
        val error = data.optInt("error_code", 0)
        check((message.isBlank() || message == "success") && error == 0) {
            data.optString("description").ifBlank { message.ifBlank { "登录接口返回错误 $error" } }
        }
        return data
    }

    private fun passportHeaders(referer: String, cookies: Map<String, String>) = okhttp3.Headers.Builder()
        .add("User-Agent", ABogusSigner.USER_AGENT)
        .add("Accept", "application/json, text/javascript, */*")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .add("Origin", "https://www.douyin.com")
        .add("Referer", referer)
        .add("sec-ch-ua", "\"Chromium\";v=\"147\", \"Not_A Brand\";v=\"8\", \"Google Chrome\";v=\"147\"")
        .add("sec-ch-ua-mobile", "?0")
        .add("sec-ch-ua-platform", "\"Windows\"")
        .add("sec-fetch-dest", "empty")
        .add("sec-fetch-mode", "cors")
        .add("sec-fetch-site", "same-site")
        .add("Cookie", cookieHeader(cookies))
        .build()

    private fun homeHeaders() = okhttp3.Headers.Builder()
        .add("User-Agent", ABogusSigner.USER_AGENT)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .add("sec-ch-ua", "\"Chromium\";v=\"147\", \"Not_A Brand\";v=\"8\", \"Google Chrome\";v=\"147\"")
        .add("sec-ch-ua-mobile", "?0")
        .add("sec-ch-ua-platform", "\"Windows\"")
        .add("sec-fetch-dest", "document")
        .add("sec-fetch-mode", "navigate")
        .add("sec-fetch-site", "none")
        .add("Referer", "https://www.douyin.com/")
        .build()

    private fun mergeCookie(cookies: MutableMap<String, String>, header: String) {
        val pair = header.substringBefore(';')
        val index = pair.indexOf('=')
        if (index > 0) cookies[pair.substring(0, index).trim()] = pair.substring(index + 1).trim()
    }

    private fun cookieHeader(cookies: Map<String, String>) = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    private fun hasSession(cookies: Map<String, String>) = !cookies["sessionid"].isNullOrBlank() || !cookies["sessionid_ss"].isNullOrBlank()
    private fun query(params: Map<String, String>) = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun verifyFp(): String = "verify_${System.currentTimeMillis().toString(36)}_${UUID.randomUUID().toString().replace("-", "").take(16)}"

    private fun randomToken(length: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-_="
        val random = SecureRandom()
        return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun decodeQr(value: String): Bitmap? = runCatching {
        val raw = value.substringAfter("base64,", value)
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
