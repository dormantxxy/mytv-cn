package com.mytv.cn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MyApplication ?: return
        when (intent.action) {
            ACTION_PAUSE, ACTION_PLAY -> app.player.toggle()
            ACTION_NEXT -> app.player.next()
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.mytv.cn.PIP_PAUSE"
        const val ACTION_PLAY = "com.mytv.cn.PIP_PLAY"
        const val ACTION_NEXT = "com.mytv.cn.PIP_NEXT"
    }
}
