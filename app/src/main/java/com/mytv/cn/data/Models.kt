package com.mytv.cn.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class RootTab(val label: String) {
    HOME("首页"), FEATURED("精选"), LIVE("直播"), SEARCH("搜索"), ME("我")
}

enum class FeedKind { RECOMMEND, FOLLOWING, MOBILE, FEATURED, RELATED, USER, MIX }

enum class ProfileTab(val label: String) {
    WORKS("作品"), LIKES("喜欢"), MIXES("合集"), COLLECTIONS("收藏"),
    WATCH_LATER("稍后再看"), FOLLOWERS("粉丝"), FOLLOWING("关注"), HISTORY("历史")
}

enum class VideoResolution(val key: String, val label: String, val targetHeight: Int) {
    AUTO("auto", "自动", 0),
    HIGHEST("highest", "最高画质", Int.MAX_VALUE),
    P1080("1080p", "高清 1080P", 1080),
    P720("720p", "标清 720P", 720),
    P540("480p", "流畅 540P", 540)
}

enum class LiveResolution(val key: String, val label: String) {
    AUTO("auto", "自动"), HIGHEST("highest", "最高画质"), HIGH("high", "高挡位"),
    MEDIUM("medium", "中挡位"), LOW("low", "低挡位")
}

enum class CodecPreference(val key: String, val label: String) {
    AUTO("auto", "自动"), H264("h264", "优先 H.264"), H265("h265", "优先 H.265")
}

enum class PlaybackEndAction(val key: String, val label: String) {
    AUTO_NEXT("auto_next", "播放下一条"), PAUSE("pause", "暂停"), REPLAY("replay", "重播")
}

enum class ClockDisplayMode(val key: String, val label: String) {
    ALWAYS("always", "始终显示"), HOURLY("hourly", "整点显示"),
    HOURLY_AND_HALF("hourly_and_half", "整点与半点"), NEVER("never", "从不显示")
}

enum class DanmakuStyle(val key: String, val label: String) {
    SCROLL("scroll", "滚动"), LIST("list", "列表")
}

enum class ThemeMode(val value: Int, val label: String) {
    AUTO(0, "自动"), LIGHT(1, "浅色"), DARK(2, "暗色")
}

enum class ColorSource(val key: String, val label: String) {
    CONTENT("content", "画面取色"), DEVICE("device", "跟随系统壁纸"), BRAND("brand", "品牌红")
}

enum class ColorStyle(val key: String, val label: String) {
    BY_SCENE("by_scene", "按场景"), VIBRANT("vibrant", "鲜明"), SOFT("soft", "柔和"), FIDELITY("fidelity", "忠实")
}

enum class ColorContrast(val key: String, val label: String) {
    STANDARD("standard", "标准"), MEDIUM("medium", "中"), HIGH("high", "高")
}

enum class PlayerSurface(val key: String, val label: String) {
    SURFACE("surface_view", "SurfaceView"), TEXTURE("texture_view", "TextureView")
}

enum class AudioBalance(val key: String, val label: String) {
    OFF("off", "关闭"), LOW("low", "低"), MEDIUM("medium", "中"), HIGH("high", "高")
}

data class VideoSource(
    val url: String,
    val quality: String = "默认",
    val codec: String = "h264",
    val width: Int = 0,
    val height: Int = 0,
    val bitRate: Long = 0,
    val fps: Int = 0
)

data class AuthorInfo(
    val uid: String = "",
    val secUid: String = "",
    val nickname: String = "",
    val signature: String = "",
    val avatar: String = "",
    val followingCount: Long = 0,
    val followerCount: Long = 0,
    val totalFavorited: Long = 0,
    val awemeCount: Long = 0,
    val isFollowing: Boolean = false
)

data class VideoStats(
    val digg: Long = 0,
    val comment: Long = 0,
    val collect: Long = 0,
    val share: Long = 0,
    val play: Long = 0
)

data class MixSummary(
    val id: String = "",
    val name: String = "",
    val currentEpisode: Int = 0,
    val totalEpisodes: Int = 0,
    val cover: String = ""
)

data class VideoItem(
    val id: String,
    val description: String = "",
    val author: AuthorInfo = AuthorInfo(),
    val cover: String = "",
    val dynamicCover: String = "",
    val sources: List<VideoSource> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val createTime: Long = 0,
    val stats: VideoStats = VideoStats(),
    val liked: Boolean = false,
    val collected: Boolean = false,
    val authenticationToken: String = "",
    val mix: MixSummary? = null,
    val raw: JsonObject = JsonObject(emptyMap())
) {
    val isVertical: Boolean get() = height > width
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height else 16f / 9f
}

data class LiveQuality(
    val key: String,
    val label: String,
    val hls: String = "",
    val flv: String = "",
    val level: Int = 0,
    val codec: String = "h264",
    val bitRate: Long = 0
) {
    val playableUrl: String get() = hls.ifBlank { flv }
}

data class LiveCamera(
    val id: String,
    val title: String,
    val roomId: String = "",
    val webRid: String = ""
)

data class LiveRoomItem(
    val roomId: String,
    val webRid: String = "",
    val title: String = "",
    val owner: AuthorInfo = AuthorInfo(),
    val cover: String = "",
    val onlineText: String = "",
    val qualities: List<LiveQuality> = emptyList(),
    val cameras: List<LiveCamera> = emptyList(),
    val status: Int = 0,
    val raw: JsonObject = JsonObject(emptyMap())
)

data class CommentItem(
    val id: String,
    val text: String,
    val user: AuthorInfo = AuthorInfo(),
    val diggCount: Long = 0,
    val replyCount: Long = 0,
    val createTime: Long = 0,
    val images: List<String> = emptyList(),
    val replies: List<CommentItem> = emptyList()
)

data class DanmakuItem(
    val id: String,
    val text: String,
    val timeMs: Long,
    val color: Long = 0xFFFFFFFF,
    val user: AuthorInfo = AuthorInfo(),
    val image: String = ""
)

data class LiveDanmakuItem(
    val id: Long,
    val text: String,
    val userName: String = "",
    val userId: String = "",
    val level: Int = 0,
    val badge: String = "",
    val onlineCount: Long = 0,
    val type: String = "chat"
)

data class ChannelItem(
    val id: String,
    val title: String,
    val icon: String = "",
    val type: String = ""
)

data class CollectionFolder(
    val id: String,
    val title: String,
    val cover: String = "",
    val count: Int = 0,
    val owner: AuthorInfo = AuthorInfo()
)

data class SearchResults(
    val videos: List<VideoItem> = emptyList(),
    val users: List<AuthorInfo> = emptyList(),
    val lives: List<LiveRoomItem> = emptyList(),
    val searchId: String = "",
    val hasMore: Boolean = false,
    val cursor: Long = 0
)

data class Page<T>(
    val items: List<T>,
    val cursor: Long = 0,
    val hasMore: Boolean = false,
    val extra: String = ""
)

@Serializable
data class InteractionSecurity(
    val rawPayload: String = "",
    val ticket: String = "",
    val tsSign: String = "",
    val privateKey: String = "",
    val serverCertificate: String = ""
) {
    val usable: Boolean get() = ticket.isNotBlank() && tsSign.isNotBlank() && privateKey.isNotBlank()
}

@Serializable
data class AccountProfile(
    val id: String,
    val name: String,
    val cookie: String = "",
    val security: InteractionSecurity = InteractionSecurity(),
    val pinHash: String = "",
    val pinSalt: String = "",
    val userId: String = ""
) {
    val pinProtected: Boolean get() = pinHash.isNotBlank() && pinSalt.isNotBlank()
}

data class ReleaseInfo(
    val tag: String,
    val name: String,
    val body: String,
    val apkUrl: String,
    val digest: String = ""
)

fun formatCount(value: Long): String {
    fun scaled(divisor: Double, suffix: String): String =
        "%.1f".format(value / divisor).trimEnd('0').trimEnd('.') + suffix
    return when {
        value >= 100_000_000 -> scaled(100_000_000.0, "亿")
        value >= 10_000 -> scaled(10_000.0, "万")
        value >= 1_000 -> scaled(1_000.0, "k")
        else -> value.toString()
    }
}
