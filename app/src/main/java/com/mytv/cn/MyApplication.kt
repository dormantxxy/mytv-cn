package com.mytv.cn

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import com.mytv.cn.data.SettingsStore
import com.mytv.cn.network.ABogusSigner
import com.mytv.cn.network.DouyinApi
import com.mytv.cn.network.LiveDanmakuClient
import com.mytv.cn.network.QrLoginManager
import com.mytv.cn.player.PlayerController
import com.mytv.cn.util.UpdateManager
import com.whl.quickjs.android.QuickJSLoader
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MyApplication : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var signer: ABogusSigner
        private set
    lateinit var api: DouyinApi
        private set
    lateinit var player: PlayerController
        private set
    lateinit var liveDanmaku: LiveDanmakuClient
        private set
    lateinit var qrLogin: QrLoginManager
        private set
    lateinit var updates: UpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        runCatching { QuickJSLoader.init() }.onFailure { Log.w("MYTV", "QuickJS loader failed", it) }
        settings = SettingsStore(this)
        signer = ABogusSigner(this)
        api = DouyinApi(settings, signer)
        player = PlayerController(this)
        liveDanmaku = LiveDanmakuClient(settings, signer)
        qrLogin = QrLoginManager(settings, signer)
        updates = UpdateManager(this)
        installCrashHandler()
        signer.prepare()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e("MYTV_CRASH", "Unhandled application crash", error)
            runCatching {
                val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val report = buildString {
                    appendLine("━━ MYTV 崩溃报告 ━━")
                    appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                    appendLine("设备: ${Build.BRAND} ${Build.MODEL}")
                    appendLine("应用版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("线程: ${thread.name}")
                    appendLine()
                    appendLine("━━ 异常信息 ━━")
                    appendLine("${error.javaClass.name}: ${error.message}")
                    error.cause?.let { appendLine("根因: ${it.javaClass.name}: ${it.message}") }
                    appendLine()
                    appendLine("━━ 堆栈跟踪 ━━")
                    append(stack)
                }
                startActivity(Intent(this, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("crash_info", report)
                })
                Thread.sleep(400)
                Process.killProcess(Process.myPid())
            }.onFailure { previous?.uncaughtException(thread, error) }
        }
    }

    override fun onTerminate() {
        liveDanmaku.close()
        player.close()
        signer.close()
        super.onTerminate()
    }
}
