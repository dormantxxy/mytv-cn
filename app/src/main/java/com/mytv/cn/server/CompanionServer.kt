package com.mytv.cn.server

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.mytv.cn.data.SettingsStore
import com.mytv.cn.network.InteractionSigner
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections

/** Original web/submit.html and web/search.html hosted on the local network. */
class CompanionServer(
    context: Context,
    private val settings: SettingsStore,
    private val onSearchInput: (String) -> Unit,
    private val onSearch: (String) -> Unit
) : Closeable {
    private val app = context.applicationContext
    private var http: HttpServer? = null
    private var socket: SearchSocket? = null
    var httpPort: Int = 0
        private set
    var socketPort: Int = 0
        private set

    val submitUrl: String? get() = localIpv4()?.let { ip -> httpPort.takeIf { it > 0 }?.let { "http://$ip:$it/" } }
    val searchUrl: String? get() = localIpv4()?.let { ip -> httpPort.takeIf { it > 0 }?.let { "http://$ip:$it/search" } }

    @Synchronized
    fun start() {
        if (http != null) return
        for (port in 9979..9997) {
            if (!portFree(port) || !portFree(port + 1)) continue
            val ws = SearchSocket(port + 1)
            val server = HttpServer(port, port + 1)
            runCatching {
                ws.start()
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }.onSuccess {
                socket = ws
                http = server
                httpPort = port
                socketPort = port + 1
                return
            }.onFailure {
                runCatching { ws.stop(300) }
                runCatching { server.stop() }
            }
        }
        error("无法启动局域网推送服务")
    }

    fun updateTvText(value: String) {
        socket?.broadcast(JSONObject().put("type", "tv_text").put("text", value).toString())
    }

    override fun close() {
        runCatching { http?.stop() }
        runCatching { socket?.stop(500) }
        http = null
        socket = null
        httpPort = 0
        socketPort = 0
    }

    private inner class HttpServer(port: Int, private val wsPort: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response = runCatching {
            when {
                session.uri == "/" && session.method == Method.GET -> html(submitPage())
                session.uri == "/search" && session.method == Method.GET -> html(searchPage())
                session.uri == "/api/submit" && session.method == Method.POST -> submit(session)
                session.uri == "/logcat" && session.method == Method.GET -> text(appLogs(), "text/plain; charset=utf-8")
                session.uri == "/health" -> json(JSONObject().put("success", true).toString())
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        }.getOrElse { error ->
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json; charset=utf-8",
                JSONObject().put("success", false).put("message", error.message ?: "请求失败").toString()
            )
        }.apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Referrer-Policy", "no-referrer")
        }

        private fun submit(session: IHTTPSession): Response {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = files["postData"].orEmpty()
            val data = JSONObject(body)
            val cookie = data.optString("cookie").trim()
            val securityPayload = data.optString("security_payload").trim()
            val privateKey = data.optString("private_key").trim()
            check(cookie.isNotBlank() || securityPayload.isNotBlank() || privateKey.isNotBlank()) {
                "请至少填写 Cookie 或互动安全凭据"
            }
            if (cookie.isNotBlank()) settings.setCookie(cookie)
            if (securityPayload.isNotBlank() || privateKey.isNotBlank()) {
                val parsed = InteractionSigner.parse(securityPayload, privateKey)
                val existing = settings.state.value.interactionSecurity
                settings.setInteractionSecurity(
                    parsed.copy(
                        ticket = parsed.ticket.ifBlank { existing.ticket },
                        tsSign = parsed.tsSign.ifBlank { existing.tsSign },
                        privateKey = parsed.privateKey.ifBlank { existing.privateKey },
                        serverCertificate = parsed.serverCertificate.ifBlank { existing.serverCertificate }
                    )
                )
            }
            return json(JSONObject().put("success", true).put("message", "推送成功，登录信息已更新").toString())
        }

        private fun submitPage(): String = template("web/submit.html")
        private fun searchPage(): String = template("web/search.html").replace(":9980", ":$wsPort")

        private fun template(path: String): String = readAsset(path)
            .replace("{{THEME_CSS}}", themeCss())
            .replace("{{PANEL_HEAD}}", readAsset("web/panel-head.html"))
            .replace("{{DEVICE_NAME}}", "${Build.MANUFACTURER} ${Build.MODEL}")

        private fun html(value: String) = text(value, "text/html; charset=utf-8")
        private fun json(value: String) = text(value, "application/json; charset=utf-8")
        private fun text(value: String, mime: String) = newFixedLengthResponse(Response.Status.OK, mime, value)
    }

    private inner class SearchSocket(port: Int) : WebSocketServer(InetSocketAddress(port)) {
        override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
            connection.send(JSONObject().put("type", "tv_text").put("text", "").toString())
        }

        override fun onMessage(connection: WebSocket, message: String) {
            runCatching {
                val json = JSONObject(message)
                val text = json.optString("text").take(200)
                when (json.optString("type")) {
                    "input" -> onSearchInput(text)
                    "search" -> onSearch(text)
                }
                broadcast(JSONObject().put("type", "tv_text").put("text", text).toString())
            }
        }

        override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) = Unit
        override fun onError(connection: WebSocket?, error: Exception) = Unit
        override fun onStart() = Unit
    }

    private fun readAsset(path: String): String = app.assets.open(path).bufferedReader().use { it.readText() }

    private fun appLogs(): String = runCatching {
        Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "500", "MYTV:V", "AndroidRuntime:E", "*:S"))
            .inputStream.bufferedReader().use { it.readText() }
    }.getOrElse { "当前系统不允许读取日志：${it.message}" }

    private fun themeCss() = """
        :root{--surface:#0e0e0f;--on-surface:#f5f5f5;--on-surface-variant:#b8b8bd;
        --primary:#fe2c55;--on-primary:#fff;--primary-container:#47101d;--on-primary-container:#ffd9df;
        --secondary-container:#173b38;--on-secondary-container:#b9f5ef;--tertiary:#25f4ee;
        --tertiary-container:#123c3a;--on-tertiary-container:#c7fffb;--error-container:#5b1520;
        --on-error-container:#ffd9de;--sc-lowest:#141416;--sc-low:#1c1c1f;--sc-high:#29292d}
    """.trimIndent()

    companion object {
        fun localIpv4(): String? {
            val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrNull().orEmpty()
            return interfaces.sortedBy { network ->
                when {
                    network.name.startsWith("wlan") -> 0
                    network.name.startsWith("eth") -> 1
                    else -> 2
                }
            }.flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }

        fun portFree(port: Int): Boolean = runCatching { ServerSocket(port).use { true } }.getOrDefault(false)

        fun qrBitmap(content: String, size: Int = 640): Bitmap {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}
