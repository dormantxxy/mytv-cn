package com.mytv.cn.network

import android.content.Context
import com.mytv.cn.data.AppSettings
import com.mytv.cn.data.AuthorInfo
import com.mytv.cn.data.ChannelItem
import com.mytv.cn.data.CollectionFolder
import com.mytv.cn.data.CommentItem
import com.mytv.cn.data.DanmakuItem
import com.mytv.cn.data.DouyinJson
import com.mytv.cn.data.LiveRoomItem
import com.mytv.cn.data.Page
import com.mytv.cn.data.SearchResults
import com.mytv.cn.data.SettingsStore
import com.mytv.cn.data.VideoItem
import com.mytv.cn.data.bool
import com.mytv.cn.data.long
import com.mytv.cn.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class DouyinApiException(message: String, val needsLogin: Boolean = false) : Exception(message)

internal const val RECOMMEND_PATH = "/aweme/v1/web/tab/feed/"
internal fun shouldSignWebRequest(path: String): Boolean = path != RECOMMEND_PATH
internal fun encodeWebQuery(params: Map<String, String>): String = params.entries
    .filter { it.key.isNotBlank() }
    .joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

/**
 * Direct Web API client reconstructed from the endpoint/parameter call chain in myDV 2.1.37.
 * It deliberately keeps parameter insertion order because a_bogus signs the exact query string.
 */
class DouyinApi(
    context: Context,
    private val settingsStore: SettingsStore,
    private val signer: ABogusSigner
) {
    private val app = context.applicationContext
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val bootstrapClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    init { signer.prepare() }

    suspend fun initializeSession() = withContext(Dispatchers.IO) {
        val existing = settingsStore.state.value.cookieMap()
        val generated = linkedMapOf(
            "s_v_web_id" to generateVerifyFp(),
            "dy_swidth" to "2560",
            "dy_sheight" to "1440",
            "device_web_cpu_core" to "24",
            "device_web_memory_size" to "8"
        ).filterKeys { existing[it].isNullOrBlank() }
        if (generated.isNotEmpty()) {
            settingsStore.mergeSetCookies(generated.map { (key, value) -> "$key=$value" })
        }
        if (settingsStore.state.value.cookieMap()["ttwid"].isNullOrBlank()) registerTtwid()
        bootstrapWebCookies()

        val refreshed = settingsStore.state.value.cookieMap()
        refreshed["__ac_nonce"]?.takeIf(String::isNotBlank)?.let { nonce ->
            runCatching { signer.acSignature(nonce) }.getOrNull()
                ?.takeIf(String::isNotBlank)
                ?.let { settingsStore.mergeSetCookies(listOf("__ac_signature=$it")) }
        }
        if (settingsStore.state.value.cookieMap()["msToken"].isNullOrBlank()) {
            fetchMsToken()?.let { settingsStore.mergeSetCookies(listOf("msToken=$it")) }
        }
    }

    suspend fun recommendation(
        page: Int = 1,
        count: Int = 15,
        mobile: Boolean = false,
        seenIds: List<String> = settingsStore.state.value.viewedVideoIds
    ): Page<VideoItem> {
        val settings = settingsStore.state.value
        val cookie = settings.cookieMap()
        val globalId = settingsStore.getOrCreateNumericId("globalwid")
        val params = linkedMapOf(
            "filterGids" to seenIds.takeLast(100).joinToString(","),
            "tag_id" to "",
            "share_aweme_id" to "",
            "live_insert_type" to "",
            "count" to count.toString(),
            "refresh_index" to (page - 1).coerceAtLeast(0).toString(),
            "video_type_select" to if (mobile) "0" else "1",
            "aweme_pc_rec_raw_data" to "{\"is_client\":false,\"ff_danmaku_status\":1,\"danmaku_switch_status\":0,\"is_dash_user\":1,\"related_recommend\":1,\"is_xigua_user\":0}",
            "pull_type" to if (page == 1) "0" else "2",
            "min_window" to "0",
            "free_right" to "0",
            "view_count" to "0",
            "plug_block" to "0",
            "ug_source" to "",
            "creative_id" to "",
            "pc_libra_divert" to "Windows",
            "support_h265" to "1",
            "support_dash" to "1",
            "webcast_sdk_version" to "170400",
            "webcast_version_code" to "170400",
            "screen_width" to (cookie["dy_swidth"] ?: "2560"),
            "screen_height" to (cookie["dy_sheight"] ?: "1440"),
            "cpu_core_num" to (cookie["device_web_cpu_core"] ?: "24"),
            "device_memory" to (cookie["device_web_memory_size"] ?: "8"),
            "globalwid" to globalId,
            "webid" to globalId
        )
        cookie["UIFID"]?.takeIf(String::isNotBlank)?.let { params["uifid"] = it }
        addUserCenterSignature(params, settings)
        return DouyinJson.videoPage(
            get(
                RECOMMEND_PATH,
                params,
                referer = "$WEB/?recommend=1",
                sign = false,
                augmentIdentity = false
            )
        )
    }

    suspend fun followingFeed(cursor: Long = 0, count: Int = 15): Page<VideoItem> {
        requireLogin()
        val params = linkedMapOf(
            "cursor" to cursor.toString(),
            "level" to "1",
            "count" to count.toString(),
            "pull_type" to "0",
            "aweme_ids" to "",
            "room_ids" to "",
            "webcast_sdk_version" to "170400",
            "webcast_version_code" to "170400"
        )
        return DouyinJson.videoPage(get("/aweme/v1/web/follow/feed/", params, referer = "$WEB/follow"))
    }

    suspend fun featuredChannels(): List<ChannelItem> =
        DouyinJson.channels(get("/aweme/v1/web/home/channel/setting/", linkedMapOf("channel" to "channel_pc_web")))

    suspend fun featuredFeed(tagId: String, page: Int = 1, count: Int = 15): Page<VideoItem> {
        val params = linkedMapOf(
            "module_id" to "3003101",
            "count" to count.toString(),
            "filterGids" to settingsStore.state.value.viewedVideoIds.takeLast(100).joinToString(","),
            "presented_ids" to "",
            "refresh_index" to (page - 1).coerceAtLeast(0).toString(),
            "refer_id" to "",
            "refer_type" to "10",
            "pull_type" to if (page == 1) "0" else "2",
            "awemePcRecRawData" to "{\"is_xigua_user\":0,\"danmaku_switch_status\":0,\"is_client\":false}",
            "install_time" to "",
            "tag_id" to tagId,
            "use_lite_type" to "0",
            "pre_log_id" to "",
            "pre_item_ids" to "",
            "pre_room_ids" to "",
            "xigua_user" to "0",
            "active_id" to tagId,
            "is_active_tab" to "false"
        )
        return DouyinJson.videoPage(get("/aweme/v2/web/module/feed/", params))
    }

    suspend fun videoDetail(id: String): VideoItem {
        val root = get(
            "/aweme/v1/web/aweme/detail/",
            linkedMapOf(
                "aweme_id" to id,
                "request_source" to "600",
                "origin_type" to "quick_player",
                "support_h265" to "1",
                "support_dash" to "1"
            ),
            referer = "$WEB/video/$id"
        )
        return DouyinJson.video(root) ?: DouyinJson.videoPage(root).items.firstOrNull()
            ?: throw DouyinApiException("视频已失效")
    }

    suspend fun related(id: String, page: Int = 1, count: Int = 20): Page<VideoItem> {
        val params = linkedMapOf(
            "aweme_id" to id,
            "count" to count.toString(),
            "filterGids" to settingsStore.state.value.viewedVideoIds.takeLast(100).joinToString(","),
            "awemePcRecRawData" to "{\"is_client\":true}",
            "sub_channel_id" to "3",
            "Seo-Flag" to "0",
            "refresh_index" to (page - 1).coerceAtLeast(0).toString()
        )
        return DouyinJson.videoPage(get("/aweme/v1/web/aweme/related/", params, referer = "$WEB/?recommend=1"))
    }

    suspend fun comments(awemeId: String, cursor: Long = 0, count: Int = 20): Page<CommentItem> =
        DouyinJson.comments(
            get(
                "/aweme/v1/web/comment/list/",
                linkedMapOf("aweme_id" to awemeId, "cursor" to cursor.toString(), "count" to count.toString()),
                referer = "$WEB/video/$awemeId"
            )
        )

    suspend fun commentReplies(awemeId: String, commentId: String, cursor: Long = 0, count: Int = 20): Page<CommentItem> =
        DouyinJson.comments(
            get(
                "/aweme/v1/web/comment/list/reply/",
                linkedMapOf(
                    "item_id" to awemeId,
                    "comment_id" to commentId,
                    "cursor" to cursor.toString(),
                    "count" to count.toString()
                ),
                referer = "$WEB/video/$awemeId"
            )
        )

    suspend fun videoDanmaku(item: VideoItem, startMs: Long, endMs: Long): List<DanmakuItem> {
        val params = linkedMapOf(
            "app_name" to "aweme",
            "format" to "json",
            "group_id" to item.id,
            "item_id" to item.id,
            "start_time" to startMs.toString(),
            "end_time" to endMs.toString(),
            "duration" to item.durationMs.toString(),
            "authentication_token" to item.authenticationToken,
            "pc_libra_divert" to "Windows",
            "support_h265" to "0",
            "support_dash" to "1",
            "version_code" to "170400",
            "version_name" to "17.4.0"
        )
        return DouyinJson.danmaku(get("/aweme/v1/web/danmaku/get_v2/", params, referer = "$WEB/video/${item.id}"))
    }

    suspend fun emojiList(): JsonObject = get("/aweme/v1/web/emoji/list", emptyMap())

    suspend fun hotWords(): List<String> =
        DouyinJson.hotWords(get("/aweme/v1/web/hot/search/list/", linkedMapOf("detail_list" to "1"), referer = "$WEB/search/"))

    suspend fun suggestions(keyword: String): List<String> =
        DouyinJson.suggestions(
            get(
                "/aweme/v1/web/search/sug/",
                linkedMapOf("keyword" to keyword, "source" to "aweme_video_web", "from_group_id" to "", "query" to "general"),
                referer = "$WEB/search/"
            )
        )

    suspend fun suggestWords(count: Int = 10, fromGroupId: String = ""): List<String> =
        DouyinJson.suggestions(
            get(
                "/aweme/v1/web/api/suggest_words/",
                linkedMapOf("count" to count.toString(), "business_id" to "30068", "from_group_id" to fromGroupId, "query" to "general"),
                referer = "$WEB/search/"
            )
        )

    suspend fun search(
        keyword: String,
        offset: Int = 0,
        count: Int = 20,
        searchId: String = "",
        videoOnly: Boolean = false,
        sortType: String = "0",
        publishTime: String = "0",
        duration: String = "0"
    ): SearchResults {
        requireLogin()
        val path = if (videoOnly) "/aweme/v1/web/search/item/" else "/aweme/v1/web/general/search/single/"
        val params = linkedMapOf(
            "search_channel" to if (videoOnly) "aweme_video_web" else "aweme_general",
            "enable_history" to "1",
            "sort_type" to sortType,
            "publish_time" to publishTime,
            "filter_duration" to duration,
            "search_range" to "0",
            "keyword" to keyword,
            "search_source" to "normal_search",
            "query_correct_type" to "1",
            "is_filter_search" to if (videoOnly) "1" else "0",
            "from_group_id" to "",
            "offset" to offset.toString(),
            "count" to count.toString(),
            "need_filter_settings" to if (offset == 0) "1" else "0",
            "list_type" to "single"
        )
        if (searchId.isNotBlank()) params["search_id"] = searchId
        return DouyinJson.search(get(path, params, referer = "$WEB/search/${encode(keyword)}"))
    }

    suspend fun liveRecommended(loadMore: Boolean = false): Page<LiveRoomItem> {
        val params = linkedMapOf(
            "need_map" to "1",
            "liveid" to "1",
            "is_draw" to "1",
            "inner_from_drawer" to "0",
            "custom_count" to if (loadMore) "8" else "15",
            "action" to if (loadMore) "load_more" else "refresh",
            "action_type" to if (loadMore) "loadmore" else "refresh",
            "enter_source" to "web_homepage_hot_web_live_card",
            "source_key" to "",
            "is_ssr" to "true",
            "maxtime" to "0"
        )
        return DouyinJson.livePage(get("/webcast/feed/", params, live = true, referer = LIVE))
    }

    suspend fun liveFollowing(scene: String = "aweme_pc_follow_top"): Page<LiveRoomItem> {
        requireLogin()
        return DouyinJson.livePage(
            get("/webcast/web/feed/follow/", linkedMapOf("scene" to scene), live = true, referer = "$LIVE/follow")
        )
    }

    suspend fun livePartition(partition: String, partitionType: String = "1", offset: Int = 0): Page<LiveRoomItem> =
        DouyinJson.livePage(
            get(
                "/webcast/web/partition/detail/room/v2/",
                linkedMapOf(
                    "partition" to partition,
                    "partition_type" to partitionType,
                    "count" to "15",
                    "offset" to offset.toString()
                ),
                live = true,
                referer = LIVE
            )
        )

    suspend fun liveInfo(roomId: String): LiveRoomItem {
        val root = get(
            "/webcast/room/info_by_scene/",
            linkedMapOf("room_id" to roomId, "scene" to "aweme_video_feed_pc", "enter_source" to "cn"),
            live = true,
            referer = LIVE
        )
        return DouyinJson.liveRoom(root) ?: DouyinJson.livePage(root).items.firstOrNull()
            ?: throw DouyinApiException("直播间已关闭")
    }

    suspend fun enterLive(webRid: String, roomId: String = ""): LiveRoomItem {
        val params = linkedMapOf("web_rid" to webRid, "room_id" to roomId, "enter_source" to "web_live")
        val userId = settingsStore.state.value.cookieMap()["user_unique_id"]
            ?: settingsStore.getOrCreateNumericId("user_unique_id")
        val stubInput = listOf(
            "live_id=1", "aid=6383", "version_code=180800", "webcast_sdk_version=1.3.0",
            "room_id=$roomId", "sub_room_id=", "sub_channel_id=", "did_rule=3",
            "user_unique_id=$userId", "device_platform=web", "device_type=", "ac=", "identity=audience"
        ).joinToString(",")
        val stub = md5(stubInput)
        signer.liveSignature(stub, ABogusSigner.USER_AGENT).takeIf(String::isNotBlank)?.let { params["_signature"] = it }
        val root = get("/webcast/room/web/enter/", params, live = true, referer = "$LIVE/$webRid")
        return DouyinJson.liveRoom(root, webRid) ?: DouyinJson.livePage(root).items.firstOrNull()
            ?: throw DouyinApiException("无法解析直播流")
    }

    suspend fun selfProfile(): AuthorInfo {
        requireLogin()
        return DouyinJson.profile(get("/aweme/v1/web/user/profile/self/", emptyMap(), referer = "$WEB/user/self", userCenter = true))
    }

    suspend fun userProfile(secUid: String): AuthorInfo =
        DouyinJson.profile(
            get(
                "/aweme/v1/web/user/profile/other/",
                linkedMapOf(
                    "sec_user_id" to secUid,
                    "source" to "channel_pc_web",
                    "publish_video_strategy_type" to "2",
                    "personal_center_strategy" to "1"
                ),
                referer = "$WEB/user/$secUid",
                userCenter = true
            )
        )

    suspend fun userPosts(secUid: String, cursor: Long = 0, count: Int = 18): Page<VideoItem> =
        DouyinJson.videoPage(
            get(
                "/aweme/v1/web/aweme/post/",
                linkedMapOf(
                    "sec_user_id" to secUid,
                    "count" to count.toString(),
                    "max_cursor" to cursor.toString(),
                    "locate_item_id" to "",
                    "locate_query" to "false",
                    "show_live_replay_strategy" to "1",
                    "need_time_list" to "0",
                    "time_list_query" to "0",
                    "whale_cut_token" to "",
                    "cut_version" to "1",
                    "publish_video_strategy_type" to "2",
                    "from_user_page" to "1",
                    "version_code" to "290100",
                    "version_name" to "29.1.0"
                ),
                referer = "$WEB/user/$secUid",
                userCenter = true
            )
        )

    suspend fun likedVideos(secUid: String, cursor: Long = 0, count: Int = 18): Page<VideoItem> {
        requireLogin()
        return DouyinJson.videoPage(
            get(
                "/aweme/v1/web/aweme/favorite/",
                linkedMapOf(
                    "sec_user_id" to secUid,
                    "count" to count.toString(),
                    "max_cursor" to cursor.toString(),
                    "min_cursor" to "0",
                    "whale_cut_token" to "",
                    "cut_version" to "1",
                    "publish_video_strategy_type" to "2"
                ),
                referer = "$WEB/user/$secUid?showTab=like",
                userCenter = true
            )
        )
    }

    suspend fun followers(userId: String, secUid: String, offset: Long = 0, count: Int = 20): Page<AuthorInfo> =
        DouyinJson.userPage(
            get(
                "/aweme/v1/web/user/follower/list/",
                userListParams(userId, secUid, offset, count),
                referer = "$WEB/user/$secUid",
                userCenter = true
            )
        )

    suspend fun following(userId: String, secUid: String, offset: Long = 0, count: Int = 20): Page<AuthorInfo> =
        DouyinJson.userPage(
            get(
                "/aweme/v1/web/user/following/list/",
                userListParams(userId, secUid, offset, count).apply { put("is_top", "1") },
                referer = "$WEB/user/$secUid",
                userCenter = true
            )
        )

    suspend fun collectionFolders(cursor: Long = 0, count: Int = 20): Page<CollectionFolder> {
        requireLogin()
        return DouyinJson.collections(
            get("/aweme/v1/web/mix/listcollection/", linkedMapOf("cursor" to cursor.toString(), "count" to count.toString()), referer = "$WEB/user/self?showTab=favorite_collection")
        )
    }

    suspend fun collectedVideos(cursor: Long = 0, count: Int = 20): Page<VideoItem> {
        requireLogin()
        return DouyinJson.videoPage(
            get("/aweme/v1/web/aweme/listcollection/", linkedMapOf("cursor" to cursor.toString(), "count" to count.toString()), referer = "$WEB/user/self?showTab=favorite_collection")
        )
    }

    suspend fun history(cursor: Long = 0, count: Int = 20): Page<VideoItem> {
        requireLogin()
        return DouyinJson.videoPage(
            get(
                "/aweme/v1/web/history/read/",
                linkedMapOf("count" to count.toString(), "max_cursor" to cursor.toString(), "directory" to "0", "category" to "0", "status" to "0"),
                referer = "$WEB/user/self?showTab=history"
            )
        )
    }

    suspend fun watchLater(offset: Int = 0): Page<VideoItem> {
        requireLogin()
        return DouyinJson.videoPage(
            get(
                "/aweme/v1/web/watchlater/list/",
                linkedMapOf("offset" to offset.toString(), "list_type" to "0", "operate_type" to "0"),
                referer = "$WEB/user/self?showTab=watchlater"
            )
        )
    }

    suspend fun seriesList(secUid: String, cursor: Long = 0, count: Int = 20): Page<CollectionFolder> =
        DouyinJson.collections(
            get(
                "/aweme/v1/web/series/list/",
                linkedMapOf("sec_user_id" to secUid, "count" to count.toString(), "cursor" to cursor.toString(), "req_from" to "channel_pc_web", "read_new_mix" to "true")
            )
        )

    suspend fun mixDetail(id: String, series: Boolean = false): JsonObject =
        get(if (series) "/aweme/v1/web/series/detail/" else "/aweme/v1/web/mix/detail/", linkedMapOf((if (series) "series_id" else "mix_id") to id))

    suspend fun mixVideos(id: String, cursor: Long = 0, count: Int = 20, series: Boolean = false): Page<VideoItem> =
        DouyinJson.videoPage(
            get(
                if (series) "/aweme/v1/web/series/aweme/" else "/aweme/v1/web/mix/aweme/",
                linkedMapOf(
                    (if (series) "series_id" else "mix_id") to id,
                    "cursor" to cursor.toString(),
                    "count" to count.toString(),
                    "pull_type" to "2",
                    "source" to "playlet_homepage_hot"
                ),
                referer = "$WEB/collection/$id"
            )
        )

    suspend fun setLiked(id: String, liked: Boolean) {
        interaction(
            "/aweme/v1/web/commit/item/digg/",
            linkedMapOf("aweme_id" to id, "item_type" to "0", "type" to if (liked) "1" else "0"),
            "$WEB/video/$id"
        )
    }

    suspend fun setCollected(id: String, collected: Boolean) {
        interaction(
            "/aweme/v1/web/aweme/collect/",
            linkedMapOf("action" to if (collected) "1" else "0", "aweme_id" to id, "aweme_type" to "0"),
            "$WEB/"
        )
    }

    suspend fun dislike(id: String) {
        interaction("/aweme/v1/web/commit/dislike/item/", linkedMapOf("aweme_id" to id), "$WEB/video/$id")
    }

    suspend fun writeHistory(authorId: String, awemeId: String) {
        runCatching {
            post(
                "/aweme/v1/web/history/write/",
                linkedMapOf("author_id" to authorId, "aweme_id" to awemeId),
                referer = "$WEB/video/$awemeId"
            )
        }
    }

    suspend fun reportPlay(awemeId: String, delta: Int = 1) {
        runCatching {
            post(
                "/aweme/v2/web/aweme/stats/",
                linkedMapOf("aweme_type" to "0", "item_id" to awemeId, "play_delta" to delta.toString(), "source" to "0")
            )
        }
    }

    suspend fun reportMixProgress(mixId: String, episode: Int, itemId: String) {
        runCatching {
            post(
                "/aweme/v1/web/mix/watch/record/",
                linkedMapOf("mix_id" to mixId, "episode" to episode.toString(), "item_id" to itemId, "scene" to "pc_web")
            )
        }
    }

    fun playbackHeaders(live: Boolean = false): Map<String, String> = if (live) {
        linkedMapOf(
            "User-Agent" to ABogusSigner.LIVE_USER_AGENT,
            "Referer" to "$LIVE/",
            "Accept" to "*/*",
            "Accept-Language" to "zh-CN",
            "Cookie" to settingsStore.state.value.cookie
        )
    } else {
        linkedMapOf(
            "User-Agent" to ABogusSigner.USER_AGENT,
            "Referer" to "$WEB/",
            "Accept" to "*/*",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            "sec-ch-ua" to "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"147\", \"Google Chrome\";v=\"147\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "video",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Site" to "cross-site",
            "Cookie" to settingsStore.state.value.cookie
        )
    }

    private suspend fun interaction(path: String, params: LinkedHashMap<String, String>, referer: String) {
        requireLogin()
        if (!settingsStore.state.value.interactionSecurity.usable) {
            throw DouyinApiException("请先在设置中补充互动安全凭据")
        }
        post(path, params, referer = referer, ticketGuard = true)
    }

    private fun userListParams(userId: String, secUid: String, offset: Long, count: Int) = linkedMapOf(
        "user_id" to userId,
        "sec_user_id" to secUid,
        "count" to count.toString(),
        "offset" to offset.toString(),
        "min_time" to "0",
        "max_time" to "0",
        "source_type" to "2",
        "gps_access" to "0",
        "address_book_access" to "0"
    )

    private suspend fun get(
        path: String,
        params: Map<String, String>,
        domain: String = WEB,
        live: Boolean = false,
        referer: String = if (live) "$LIVE/" else "$WEB/",
        userCenter: Boolean = false,
        sign: Boolean = shouldSignWebRequest(path),
        augmentIdentity: Boolean = true
    ): JsonObject = request(path, params, domain, live, referer, false, userCenter, false, sign, augmentIdentity)

    private suspend fun post(
        path: String,
        params: Map<String, String>,
        domain: String = WEB,
        live: Boolean = false,
        referer: String = if (live) "$LIVE/" else "$WEB/",
        ticketGuard: Boolean = false
    ): JsonObject = request(path, params, domain, live, referer, true, false, ticketGuard, true, true)

    private suspend fun request(
        path: String,
        endpointParams: Map<String, String>,
        domain: String,
        live: Boolean,
        referer: String,
        post: Boolean,
        userCenter: Boolean,
        ticketGuard: Boolean,
        sign: Boolean,
        augmentIdentity: Boolean
    ): JsonObject {
        val settings = settingsStore.state.value
        val params = commonParams(live).apply { putAll(endpointParams) }
        if (augmentIdentity) addIdentity(params, settings)
        val guardHeaders = if (ticketGuard) {
            InteractionSigner.ticketGuardParameters(path, settings.interactionSecurity)
        } else emptyMap()
        var query = encodeWebQuery(params)
        if (sign) {
            val signature = if (live) signer.liveABogus(query, "", path) else signer.aBogus(query, "", path)
            if (signature.isNotBlank()) params["a_bogus"] = signature
        }
        if (userCenter) addUserCenterSignature(params, settings)
        query = encodeWebQuery(params)
        val url = "$domain$path?$query"
        val request = Request.Builder()
            .url(url)
            .headers(headers(referer, settings.cookie, live, guardHeaders))
            .apply {
                if (post) post(FormBody.Builder().build()) else get()
            }
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): JsonObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            settingsStore.mergeSetCookies(response.headers("Set-Cookie"))
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw DouyinApiException("网络请求失败 (${response.code})", response.code == 401 || response.code == 403)
            }
            response.header("bd-tt-error-code")?.takeUnless { it == "0" || it.isBlank() }?.let {
                throw DouyinApiException("抖音风控错误 $it")
            }
            if (body.isBlank()) throw DouyinApiException("服务器返回空内容，Cookie 或签名可能已失效")
            val root = runCatching { DouyinJson.parse(body) }
                .getOrElse { throw DouyinApiException("响应解析失败：${it.message}") }
            checkApiStatus(root)
            root
        }
    }

    private fun checkApiStatus(root: JsonObject) {
        val code = root.long("status_code", "statusCode", "code")
        if (code == 0L) return
        val message = root.str("status_msg", "status_message", "message", "prompts")
        if (code != 0L) {
            val needsLogin = code in setOf(8L, 9L, 1009L, 2190008L) || message.contains("登录") || message.contains("cookie", true)
            throw DouyinApiException(message.ifBlank { "服务器返回错误 ($code)" }, needsLogin)
        }
    }

    private fun commonParams(live: Boolean): LinkedHashMap<String, String> = linkedMapOf(
        "device_platform" to if (live) "web" else "webapp",
        "aid" to "6383",
        "channel" to "channel_pc_web",
        "update_version_code" to "170400",
        "pc_client_type" to "1",
        "pc_libra_divert" to "Windows",
        "support_h265" to "1",
        "support_dash" to "1",
        "version_code" to "170400",
        "version_name" to "17.4.0",
        "cookie_enabled" to "true",
        "screen_width" to "2560",
        "screen_height" to "1440",
        "browser_language" to "zh-CN",
        "browser_platform" to "Win32",
        "browser_name" to "Chrome",
        "browser_version" to "147.0.0.0",
        "browser_online" to "true",
        "engine_name" to "Blink",
        "engine_version" to "147.0.0.0",
        "os_name" to "Windows",
        "os_version" to "10",
        "platform" to "PC",
        "downlink" to "10",
        "effective_type" to "4g",
        "round_trip_time" to "50"
    ).apply {
        if (live) {
            put("app_name", "douyin_web")
            put("live_id", "1")
            put("enter_from", "web_live")
            put("language", "zh-CN")
            put("tz_name", "Asia/Shanghai")
            put("webcast_sdk_version", "1.3.0")
            put("did_rule", "3")
            put("identity", "audience")
        }
    }

    private fun addIdentity(params: LinkedHashMap<String, String>, settings: AppSettings) {
        val cookie = settings.cookieMap()
        cookie["msToken"]?.takeIf(String::isNotBlank)?.let { params["msToken"] = it }
        params["screen_width"] = cookie["dy_swidth"] ?: params["screen_width"].orEmpty()
        params["screen_height"] = cookie["dy_sheight"] ?: params["screen_height"].orEmpty()
        params["cpu_core_num"] = cookie["device_web_cpu_core"] ?: "24"
        params["device_memory"] = cookie["device_web_memory_size"] ?: "8"
        cookie["s_v_web_id"]?.let {
            params["verifyFp"] = it
            params["fp"] = it
        }
        (cookie["UIFID"] ?: cookie["UIFID_TEMP"])?.takeIf(String::isNotBlank)?.let { params["uifid"] = it }
        params["webid"] = cookie["webid"] ?: settingsStore.getOrCreateNumericId("globalwid")
    }

    private fun addUserCenterSignature(params: LinkedHashMap<String, String>, settings: AppSettings) {
        val uifid = params["uifid"].orEmpty()
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        params["timestamp"] = timestamp
        if (uifid.isNotBlank()) params["x-secsdk-web-signature"] = InteractionSigner.userCenterSignature(params, uifid, timestamp)
    }

    private fun headers(
        referer: String,
        cookie: String,
        live: Boolean,
        extra: Map<String, String> = emptyMap()
    ): Headers {
        val builder = Headers.Builder()
        if (live) {
            builder
                .add("Accept", "application/json, text/plain, */*")
                .add("Accept-Language", "zh-CN")
                .add("Referer", referer)
                .add("User-Agent", ABogusSigner.LIVE_USER_AGENT)
        } else {
            builder
                .add("accept", "application/json, text/plain, */*")
                .add("accept-language", "zh-CN,zh;q=0.9,en;q=0.8")
                .add("cache-control", "no-cache")
                .add("pragma", "no-cache")
                .add("priority", "u=1, i")
                .add("sec-ch-ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"147\", \"Google Chrome\";v=\"147\"")
                .add("sec-ch-ua-mobile", "?0")
                .add("sec-ch-ua-platform", "\"Windows\"")
                .add("sec-fetch-dest", "empty")
                .add("sec-fetch-mode", "cors")
                .add("sec-fetch-site", "same-origin")
                .add("user-agent", ABogusSigner.USER_AGENT)
                .add("referer", referer)
        }
        if (cookie.isNotBlank()) builder.add("Cookie", cookie)
        extra.forEach { (name, value) -> builder.add(name, value) }
        return builder.build()
    }

    private fun requireLogin() {
        if (!settingsStore.state.value.loggedIn) {
            throw DouyinApiException("需要登录才能查看此内容，请在设置中配置完整登录 Cookie", true)
        }
    }

    private fun registerTtwid() {
        val body = "{\"region\":\"cn\",\"aid\":1768,\"needFid\":false,\"service\":\"www.ixigua.com\",\"migrate_info\":{\"ticket\":\"\",\"source\":\"node\"},\"cbUrlProtocol\":\"https\",\"union\":true}"
        val request = Request.Builder()
            .url("https://ttwid.bytedance.com/ttwid/union/register/")
            .header("User-Agent", ABogusSigner.USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", WEB)
            .header("Referer", "$WEB/")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                settingsStore.mergeSetCookies(response.headers("Set-Cookie"))
                val text = response.body?.string().orEmpty()
                Regex("\\\"ttwid\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(text)?.groupValues?.getOrNull(1)?.let {
                    settingsStore.mergeSetCookies(listOf("ttwid=$it"))
                }
            }
        }
    }

    private fun bootstrapWebCookies() {
        var target = "$WEB/?recommend=1"
        repeat(5) {
            val cookie = settingsStore.state.value.cookie
            val request = Request.Builder()
                .url(target)
                .header("User-Agent", ABogusSigner.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept-Encoding", "identity")
                .header("sec-fetch-site", "none")
                .header("sec-fetch-mode", "navigate")
                .header("sec-fetch-user", "?1")
                .header("sec-fetch-dest", "document")
                .header("sec-ch-ua-platform", "\"Windows\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Cookie", cookie)
                .get()
                .build()
            val next = runCatching {
                bootstrapClient.newCall(request).execute().use { response ->
                    settingsStore.mergeSetCookies(response.headers("Set-Cookie"))
                    if (response.code !in 300..399) return
                    response.header("Location")?.let(response.request.url::resolve)?.toString()
                }
            }.getOrNull() ?: return
            target = next
        }
    }

    private fun fetchMsToken(): String? = runCatching {
        val payload = app.assets.open("runtime/mstoken_payload.json").bufferedReader().use { it.readText() }
            .let { JSONObject(it) }
            .put("tspFromClient", System.currentTimeMillis() / 1000)
            .toString()
        val request = Request.Builder()
            .url("https://mssdk.bytedance.com/web/r/token?ms_appid=6383&msToken=1YFEsL4NQgAICNSpgOo3_Yv-ue-AhfJaweBQP0xAJRNQfOqWBTklpusHaMMr80OxFFahFPSxag23YunZwRWW9PXPneC35kCHv00w-2_iQH8PynyH34b8TXekDayIhN2BQAklnxxsbompAg_WQcoErnxP8gBiWXsSkIRlw32-eUYRQu1cDIAd5Q%3D%3D")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
            .header("Content-Type", "text/plain;charset=UTF-8")
            .build()
        client.newCall(request).execute().use { response ->
            response.headers("Set-Cookie").asSequence()
                .mapNotNull { it.substringBefore(';').takeIf { pair -> pair.startsWith("msToken=") } }
                .map { it.substringAfter('=') }
                .firstOrNull { it.length == 120 || it.length == 128 }
        }
    }.getOrNull()

    private fun generateVerifyFp(): String = buildString {
        append("verify_")
        append(randomHex(8))
        append('_').append(randomHex(4))
        append('_').append(randomHex(4))
        append('_').append(randomHex(4))
        append('_').append(randomHex(12))
    }

    private fun randomHex(length: Int): String {
        val alphabet = "0123456789abcdef"
        val random = SecureRandom()
        return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val WEB = "https://www.douyin.com"
        private const val LIVE = "https://live.douyin.com"
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
