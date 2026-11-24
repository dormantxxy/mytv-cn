package com.mytv.cn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra("crash_info") ?: "未知错误"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.rgb(17, 17, 18))
        }
        root.addView(TextView(this).apply {
            text = "应用崩溃了"
            textSize = 28f
            setTextColor(Color.rgb(254, 44, 85))
            setPadding(0, 0, 0, 16)
        })
        root.addView(TextView(this).apply {
            text = "请复制以下信息反馈给开发者"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 24)
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@CrashActivity).apply {
                text = "复制日志"
                setOnClickListener {
                    (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(
                        ClipData.newPlainText("crash_log", report)
                    )
                    Toast.makeText(this@CrashActivity, "已复制", Toast.LENGTH_SHORT).show()
                }
            })
            addView(Button(this@CrashActivity).apply {
                text = "重启应用"
                setOnClickListener {
                    packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(launch)
                    }
                    finishAffinity()
                }
            })
        })
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            addView(TextView(this@CrashActivity).apply {
                text = report
                textSize = 11f
                setTextColor(Color.LTGRAY)
                setBackgroundColor(Color.rgb(24, 24, 27))
                setPadding(24, 24, 24, 24)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
        })
        setContentView(root)
    }
}
