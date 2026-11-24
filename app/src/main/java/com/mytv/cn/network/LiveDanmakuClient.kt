package com.mytv.cn.network

import android.util.Log
import com.mytv.cn.data.LiveDanmakuItem
import com.mytv.cn.data.LiveRoomItem
import com.mytv.cn.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Webcast WebSocket + protobuf client, with /webcast/im/fetch long-poll fallback. */
class LiveDanmakuClient(
    private val settings: SettingsStore,
    private val signer: ABogusSigner
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val mutableMessages = MutableStateFlow<List<LiveDanmakuItem>>(emptyList())
    val messages: StateFlow<List<LiveDanmakuItem>> = mutableMessages.asStateFlow()
    private val mutableConnected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()

    private var socket: WebSocket? = null
    private var room: LiveRoomItem? = null
    private var fallback: Job? = null
    private var cursor = ""
    private var reconnects = 0

    fun connect(target: LiveRoomItem) {
        if (target.roomId == room?.roomId && socket != null) return
        disconnect()
        room = target
        mutableMessages.value = emptyList()
        scope.launch { open(target) }
    }

    fun disconnect() {
        fallback?.cancel()
        fallback = null
        socket?.close(1000, "room changed")
        socket = null
        room = null
        mutableConnected.value = false
        reconnects = 0
        cursor = ""
    }

    private suspend fun open(target: LiveRoomItem) {
        val userId = settings.state.value.cookieMap()["user_unique_id"]
            ?: settings.getOrCreateNumericId("user_unique_id")
        val signature = signer.liveSignature(liveStub(target.roomId, userId), LIVE_UA)
        val now = System.currentTimeMillis()
        val base = "wss://webcast5-ws-web-lf.douyin.com/webcast/im/push/v2/".toHttpUrl().newBuilder()
            .addQueryParameter("app_name", "douyin_web")
            .addQueryParameter("version_code", "180800")
            .addQueryParameter("webcast_sdk_version", "1.3.0")
            .addQueryParameter("update_version_code", "1.3.0")
            .addQueryParameter("compress", "gzip")
            .addQueryParameter("cursor", "h-1_t-${now}_r-1_d-1_u-1")
            .addQueryParameter("host", "https://live.douyin.com")
            .addQueryParameter("aid", "6383")
            .addQueryParameter("live_id", "1")
            .addQueryParameter("did_rule", "3")
            .addQueryParameter("debug", "false")
            .addQueryParameter("maxCacheMessageNumber", "20")
            .addQueryParameter("endpoint", "live_pc")
            .addQueryParameter("support_wrds", "1")
            .addQueryParameter("im_path", "/webcast/im/fetch/")
            .addQueryParameter("user_unique_id", userId)
            .addQueryParameter("device_platform", "web")
            .addQueryParameter("cookie_enabled", "true")
            .addQueryParameter("screen_width", "1920")
            .addQueryParameter("screen_height", "1080")
            .addQueryParameter("browser_language", "zh-CN")
            .addQueryParameter("browser_platform", "Win32")
            .addQueryParameter("browser_name", "Mozilla")
            .addQueryParameter("browser_version", LIVE_UA.substringAfter("Mozilla/"))
            .addQueryParameter("browser_online", "true")
            .addQueryParameter("tz_name", "Asia/Shanghai")
            .addQueryParameter("identity", "audience")
            .addQueryParameter("room_id", target.roomId)
            .addQueryParameter("heartbeatDuration", "10")
            .apply { if (signature.isNotBlank()) addQueryParameter("signature", signature) }
            .build()
        val request = Request.Builder()
            .url(base)
            .header("User-Agent", LIVE_UA)
            .header("Origin", "https://live.douyin.com")
            .header("Cookie", settings.state.value.cookie)
            .build()
        socket = client.newWebSocket(request, listener(target))
    }

    private fun listener(target: LiveRoomItem) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            mutableConnected.value = true
            reconnects = 0
            fallback?.cancel()
            fallback = null
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            parseFrame(bytes.toByteArray())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            mutableConnected.value = false
            if (room?.roomId == target.roomId) reconnect(target)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "Live WebSocket failed", t)
            mutableConnected.value = false
            if (room?.roomId == target.roomId) reconnect(target)
        }
    }

    private fun reconnect(target: LiveRoomItem) {
        socket = null
        if (reconnects < 3) {
            reconnects++
            scope.launch {
                delay(1_000L * reconnects)
                if (room?.roomId == target.roomId) open(target)
            }
        } else if (fallback == null) {
            fallback = scope.launch { longPoll(target) }
        }
    }

    private suspend fun longPoll(target: LiveRoomItem) {
        while (isActive && room?.roomId == target.roomId) {
            runCatching {
                val url = "https://live.douyin.com/webcast/im/fetch/".toHttpUrl().newBuilder()
                    .addQueryParameter("app_name", "douyin_web")
                    .addQueryParameter("version_code", "180800")
                    .addQueryParameter("webcast_sdk_version", "1.3.0")
                    .addQueryParameter("room_id", target.roomId)
                    .addQueryParameter("user_unique_id", settings.getOrCreateNumericId("user_unique_id"))
                    .addQueryParameter("cursor", cursor)
                    .addQueryParameter("resp_content_type", "protobuf")
                    .build()
                val request = Request.Builder().url(url)
                    .header("User-Agent", LIVE_UA)
                    .header("Referer", "https://live.douyin.com/${target.webRid}")
                    .header("Cookie", settings.state.value.cookie)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.bytes()?.let(::parseFrame)
                }
            }
            delay(2_000)
        }
    }

    private fun parseFrame(input: ByteArray) {
        runCatching {
            val outer = ProtoWire.parse(ProtoWire.ungzip(input))
            val payload = outer.bytes(8).firstOrNull()?.let(ProtoWire::ungzip) ?: ProtoWire.ungzip(input)
            val response = ProtoWire.parse(payload)
            response.string(2).takeIf(String::isNotBlank)?.let { cursor = it }
            val parsed = response.bytes(1).mapNotNull(::parseMessage)
            if (parsed.isNotEmpty()) {
                mutableMessages.value = (mutableMessages.value + parsed).takeLast(200)
            }
        }.onFailure { Log.w(TAG, "Live protobuf decode failed", it) }
    }

    private fun parseMessage(bytes: ByteArray): LiveDanmakuItem? {
        val message = ProtoWire.parse(bytes)
        val method = message.string(1)
        val payload = message.bytes(2).firstOrNull() ?: return null
        val id = message.vars(3).firstOrNull() ?: System.nanoTime()
        val body = ProtoWire.parse(payload)
        return when {
            method.contains("ChatMessage") -> {
                val user = body.bytes(2).firstOrNull()?.let(ProtoWire::parse)
                LiveDanmakuItem(
                    id = id,
                    text = body.string(3).ifBlank { readableStrings(body).lastOrNull().orEmpty() },
                    userName = user?.string(3).orEmpty(),
                    userId = user?.vars(1)?.firstOrNull()?.toString().orEmpty(),
                    level = user?.allVars()?.filter { it in 1..100 }?.maxOrNull()?.toInt() ?: 0,
                    type = "chat"
                ).takeIf { it.text.isNotBlank() }
            }
            method.contains("RoomUserSeqMessage") -> {
                val online = body.allVars().filter { it in 1..100_000_000 }.maxOrNull() ?: return null
                LiveDanmakuItem(id, "", onlineCount = online, type = "online")
            }
            method.contains("MemberMessage") -> {
                val user = body.bytes(2).firstOrNull()?.let(ProtoWire::parse)
                val name = user?.string(3).orEmpty()
                if (name.isBlank()) null else LiveDanmakuItem(id, "$name 进入直播间", name, type = "member")
            }
            else -> null
        }
    }

    private fun readableStrings(message: ProtoMessage): List<String> = message.allBytes().mapNotNull { bytes ->
        runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()?.takeIf { value ->
            value.length in 1..300 && value.none { it.code in 0..8 }
        }
    }

    private fun liveStub(roomId: String, userId: String): String {
        val source = listOf(
            "live_id=1", "aid=6383", "version_code=180800", "webcast_sdk_version=1.3.0",
            "room_id=$roomId", "sub_room_id=", "sub_channel_id=", "did_rule=3",
            "user_unique_id=$userId", "device_platform=web", "device_type=", "ac=", "identity=audience"
        ).joinToString(",")
        return MessageDigest.getInstance("MD5").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    override fun close() {
        disconnect()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private const val TAG = "LiveDanmaku"
        private const val LIVE_UA = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.5845.97 Safari/537.36 Core/1.116.567.400 QQBrowser/19.7.6764.400"
    }
}
