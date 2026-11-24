package com.mytv.cn

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mytv.cn.data.AppSettings
import com.mytv.cn.player.PlayerState
import com.mytv.cn.ui.MainViewModel
import com.mytv.cn.ui.MyTvApp
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var inPictureInPicture by mutableStateOf(false)
    private var lastBackPressedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()
        handleDeepLink(intent)
        installBackHandler()
        observePictureInPicture()
        setContent { MyTvApp(viewModel, inPictureInPicture) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val state = viewModel.playerState.value
            if (viewModel.settings.value.pipEnabled &&
                state.isPlaying &&
                (state.currentVideo != null || state.currentLive != null)
            ) {
                runCatching { enterPictureInPictureMode(pictureInPictureParams(state)) }
            }
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETPHOOK -> {
                    viewModel.togglePlayback()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    viewModel.player.play()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_STOP -> {
                    viewModel.player.pause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_PAGE_DOWN -> {
                    viewModel.nextVideo()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_PAGE_UP -> {
                    viewModel.previousVideo()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.handleBack()) return
                val now = SystemClock.elapsedRealtime()
                if (viewModel.settings.value.disableDoubleBackExit || now - lastBackPressedAt < 2_000) {
                    finish()
                } else {
                    lastBackPressedAt = now
                    Toast.makeText(this@MainActivity, "再按一次返回退出", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun observePictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.playerState, viewModel.settings) { player, settings -> player to settings }
                    .collect { (player, settings) -> updatePictureInPicture(player, settings) }
            }
        }
    }

    private fun updatePictureInPicture(state: PlayerState, settings: AppSettings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !settings.pipEnabled) return
        runCatching { setPictureInPictureParams(pictureInPictureParams(state)) }
    }

    private fun pictureInPictureParams(state: PlayerState): PictureInPictureParams {
        val ratio = state.aspectRatio.coerceIn(.5f, 2.39f)
        val actions = buildList {
            if (state.isPlaying) {
                add(pipAction(R.drawable.pip_pause, R.string.pip_pause, PipActionReceiver.ACTION_PAUSE, 1))
            } else {
                add(pipAction(R.drawable.pip_play, R.string.pip_play, PipActionReceiver.ACTION_PLAY, 2))
            }
            if (state.currentVideo != null) {
                add(pipAction(R.drawable.pip_next, R.string.pip_next, PipActionReceiver.ACTION_NEXT, 3))
            }
        }
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1_000).roundToInt(), 1_000))
            .setActions(actions)
            .build()
    }

    private fun pipAction(icon: Int, title: Int, action: String, requestCode: Int): RemoteAction {
        val label = getString(title)
        val intent = Intent(this, PipActionReceiver::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return RemoteAction(
            Icon.createWithResource(this, icon),
            label,
            label,
            PendingIntent.getBroadcast(this, requestCode, intent, flags)
        )
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        val segments = data.pathSegments
        val index = segments.indexOf("video")
        if (index >= 0) segments.getOrNull(index + 1)?.let(viewModel::openVideoId)
    }
}
