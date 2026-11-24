package com.mytv.cn.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.mytv.cn.data.AppSettings
import com.mytv.cn.data.DouyinJson
import com.mytv.cn.data.LiveQuality
import com.mytv.cn.data.LiveRoomItem
import com.mytv.cn.data.PlaybackEndAction
import com.mytv.cn.data.VideoItem
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
import java.io.Closeable


data class PlayerState(
    val isPlaying: Boolean = false,
    val loading: Boolean = false,
    val error: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val aspectRatio: Float = 16f / 9f,
    val currentVideo: VideoItem? = null,
    val currentLive: LiveRoomItem? = null,
    val liveQuality: LiveQuality? = null
)

class PlayerController(context: Context) : Closeable {
    private val app = context.applicationContext
    private val dataSource = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
    lateinit var player: ExoPlayer
        private set
    private lateinit var mediaSession: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = mutable.asStateFlow()
    private val videoById = LinkedHashMap<String, VideoItem>()
    private var completionAction = PlaybackEndAction.AUTO_NEXT
    private var completionCallback: (() -> Unit)? = null
    private var poll: Job? = null

    init {
        player = ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                setWakeMode(C.WAKE_MODE_NETWORK)
                setAudioAttributes(
                    AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA).build(),
                    true
                )
                addListener(playerListener())
            }
        mediaSession = MediaSession.Builder(app, player).build()
        startPolling()
    }

    fun playVideos(
        videos: List<VideoItem>,
        startIndex: Int,
        settings: AppSettings,
        headers: Map<String, String>,
        onAutoNext: (() -> Unit)? = null
    ) {
        val playable = videos.mapNotNull { item ->
            DouyinJson.selectVideoSource(item, settings)?.let { source -> item to source.url }
        }
        if (playable.isEmpty()) {
            mutable.value = mutable.value.copy(error = "没有可播放的视频", loading = false)
            return
        }
        videoById.clear()
        playable.forEach { (item, _) -> videoById[item.id] = item }
        dataSource.setDefaultRequestProperties(headers.filterValues(String::isNotBlank))
        completionAction = settings.playbackEndAction
        completionCallback = onAutoNext
        player.repeatMode = when (settings.playbackEndAction) {
            PlaybackEndAction.REPLAY -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player.playbackParameters = PlaybackParameters(settings.playbackSpeed)
        val items = playable.map { (item, url) ->
            MediaItem.Builder().setMediaId(item.id).setUri(url).setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(item.description)
                    .setArtist(item.author.nickname)
                    .setArtworkUri(item.cover.takeIf(String::isNotBlank)?.let(android.net.Uri::parse))
                    .build()
            ).build()
        }
        val selectedId = videos.getOrNull(startIndex)?.id
        val playableIndex = items.indexOfFirst { it.mediaId == selectedId }.coerceAtLeast(0)
        player.setMediaItems(items, playableIndex, 0)
        mutable.value = PlayerState(loading = true, currentVideo = videoById[items[playableIndex].mediaId])
        player.prepare()
        player.playWhenReady = true
    }

    fun playVideo(item: VideoItem, settings: AppSettings, headers: Map<String, String>) =
        playVideos(listOf(item), 0, settings, headers)

    fun playLive(room: LiveRoomItem, quality: LiveQuality, headers: Map<String, String>) {
        if (quality.playableUrl.isBlank()) {
            mutable.value = mutable.value.copy(error = "直播流不可用", loading = false)
            return
        }
        videoById.clear()
        dataSource.setDefaultRequestProperties(headers.filterValues(String::isNotBlank))
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.playbackParameters = PlaybackParameters(1f)
        player.setMediaItem(
            MediaItem.Builder().setMediaId("live:${room.roomId}:${quality.key}")
                .setUri(quality.playableUrl)
                .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(1.02f).build())
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder().setTitle(room.title)
                        .setArtist(room.owner.nickname).build()
                ).build()
        )
        mutable.value = PlayerState(loading = true, currentLive = room, liveQuality = quality)
        player.prepare()
        player.playWhenReady = true
    }

    fun toggle() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun pause() = player.pause()
    fun resume() = player.play()
    fun next() { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
    fun previous() { if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() }
    fun seekBy(deltaMs: Long) = player.seekTo((player.currentPosition + deltaMs).coerceAtLeast(0))
    fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))
    fun setSpeed(speed: Float) { player.playbackParameters = PlaybackParameters(speed) }

    private fun playerListener() = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            mutable.value = mutable.value.copy(isPlaying = isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            mutable.value = mutable.value.copy(
                loading = playbackState == Player.STATE_BUFFERING,
                durationMs = player.duration.coerceAtLeast(0)
            )
            if (playbackState == Player.STATE_ENDED) {
                when (completionAction) {
                    PlaybackEndAction.AUTO_NEXT -> if (player.hasNextMediaItem()) player.seekToNextMediaItem() else completionCallback?.invoke()
                    PlaybackEndAction.PAUSE -> player.pause()
                    PlaybackEndAction.REPLAY -> player.seekTo(0)
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val item = mediaItem?.mediaId?.let(videoById::get)
            if (item != null) mutable.value = mutable.value.copy(currentVideo = item, currentLive = null, error = "")
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height > 0) {
                mutable.value = mutable.value.copy(
                    aspectRatio = (videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height).coerceIn(.47f, 2.39f)
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            mutable.value = mutable.value.copy(error = error.localizedMessage ?: "播放失败", loading = false)
        }
    }

    private fun startPolling() {
        poll?.cancel()
        poll = scope.launch {
            while (isActive) {
                mutable.value = mutable.value.copy(
                    positionMs = player.currentPosition.coerceAtLeast(0),
                    durationMs = player.duration.coerceAtLeast(0)
                )
                delay(250)
            }
        }
    }

    override fun close() {
        poll?.cancel()
        scope.cancel()
        mediaSession.release()
        player.release()
    }
}
