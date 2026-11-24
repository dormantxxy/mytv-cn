package com.mytv.cn.network

import android.content.Context
import android.util.Log
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** QuickJS signer reconstructed from myDV 2.1.37's ABogusSigner call chain. */
class ABogusSigner(context: Context) : Closeable {
    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "quickjs-abogus").apply { isDaemon = true }
    }

    @Volatile private var context: QuickJSContext? = null
    @Volatile private var ready = false
    @Volatile private var acReady = false

    init {
        runCatching { QuickJSLoader.init() }
            .onFailure { Log.w(TAG, "QuickJS native loader unavailable", it) }
    }

    fun prepare() {
        executor.submit { runCatching(::ensureContext) }
    }

    suspend fun aBogus(query: String, body: String = "", target: String): String =
        signABogus(query, body, target, null)

    suspend fun liveABogus(query: String, body: String = "", target: String): String =
        signABogus(query, body, target, LIVE_USER_AGENT)

    private suspend fun signABogus(
        query: String,
        body: String,
        target: String,
        userAgentOverride: String?
    ): String = onSignerThread {
        ensureContext()
        val ctx = context ?: return@onSignerThread ""
        parseSignedValue(ctx.evaluate(buildABogusScript(query, body, target, userAgentOverride))?.toString().orEmpty())
    }

    suspend fun acSignature(url: String): String = onSignerThread {
        ensureContext()
        if (!acReady) return@onSignerThread ""
        val value = context?.evaluate(
            "(function(){try{return globalThis.__ac.sign('',${JSONObject.quote(url)});}catch(e){return '';}})()"
        )?.toString().orEmpty()
        value.takeIf { it.length > 20 }.orEmpty()
    }

    suspend fun liveSignature(msStub: String, userAgent: String): String = onSignerThread {
        ensureContext()
        val ctx = context ?: return@onSignerThread ""
        repeat(6) {
            val value = ctx.evaluate(
                "(function(){try{return getMSSDKSignature(${JSONObject.quote(msStub)},${JSONObject.quote(userAgent)});}catch(e){return '';}})()"
            )?.toString().orEmpty()
            if (value.isNotBlank() && '-' !in value && '=' !in value) return@onSignerThread value
        }
        ""
    }

    private suspend fun <T> onSignerThread(block: () -> T): T = withContext(Dispatchers.IO) {
        executor.submit(Callable(block)).get(35, TimeUnit.SECONDS)
    }

    private fun ensureContext() {
        if (ready && context != null) return
        val ctx = QuickJSContext.create().also { it.setMaxStackSize(1024 * 1024) }
        try {
            ctx.evaluate(readAsset("runtime/browser_shim.js"), "browser_shim.js")
            ctx.evaluate(readAsset("abogus_dy.js").replace("\r", ""), "abogus_dy.js")
            val initialized = ctx.evaluate("initABogus()") as? Boolean ?: false
            check(initialized) { "initABogus returned false" }
            val available = ctx.evaluate("typeof getABogus === 'function'") as? Boolean ?: false
            check(available) { "getABogus is missing" }
            context = ctx
            ready = true
            initializeAcSignature(ctx)
            Log.i(TAG, "QuickJS signer ready")
        } catch (error: Throwable) {
            runCatching { ctx.destroy() }
            context = null
            ready = false
            throw error
        }
    }

    private fun initializeAcSignature(ctx: QuickJSContext) {
        acReady = runCatching {
            val source = readAsset("sign.js").replace("\r", "")
                .replace("crawler = _0x5c2014", "crawler = _0x5c2014; globalThis.__ac = crawler")
            ctx.evaluate(source, "sign.js")
            runCatching {
                ctx.evaluate("getMSSDKSignature('', ${JSONObject.quote(USER_AGENT)})")
            }
            val exists = ctx.evaluate(
                "typeof globalThis.__ac !== 'undefined' && typeof globalThis.__ac.init === 'function'"
            ) as? Boolean ?: false
            if (exists) ctx.evaluate("globalThis.__ac.init({aid:99999999,dfp:0})")
            exists
        }.getOrElse {
            Log.w(TAG, "Optional __ac_signature runtime unavailable", it)
            false
        }
    }

    private fun parseSignedValue(raw: String): String {
        val normalized = raw.trim().replace("\\u003d", "=").replace("\\u002b", "+")
            .replace("\\u002f", "/").replace("\\/", "/")
        if (normalized.isBlank() || normalized == "null" || normalized == "undefined") return ""
        return runCatching {
            val json = JSONObject(normalized)
            if (json.optBoolean("ok")) json.optString("value") else ""
        }.getOrElse { normalized.takeIf { it.length > 20 }.orEmpty() }
    }

    private fun readAsset(path: String): String =
        app.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    override fun close() {
        executor.submit {
            runCatching { context?.destroy() }
            context = null
            ready = false
            acReady = false
        }
        executor.shutdown()
    }

    companion object {
        private const val TAG = "ABogusSigner"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
        const val LIVE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.5845.97 Safari/537.36 Core/1.116.567.400 QQBrowser/19.7.6764.400"
    }
}

internal fun buildABogusScript(
    query: String,
    body: String,
    target: String,
    userAgentOverride: String?
): String {
    val userAgent = userAgentOverride?.let { JsonPrimitive(it).toString() } ?: "undefined"
    return "(function(){try{return getABogus(" +
        "${JsonPrimitive(query)},${JsonPrimitive(body)},${JsonPrimitive(target)},$userAgent" +
        ");}catch(e){return JSON.stringify({ok:false,error:String(e)});}})()"
}
