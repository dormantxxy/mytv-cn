package com.mytv.cn.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

object DouyinJson {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    fun parse(text: String): JsonObject =
        (json.parseToJsonElement(text) as? JsonObject) ?: JsonObject(emptyMap())

    fun videoPage(root: JsonObject): Page<VideoItem> {
        val videos = collectObjects(root) { obj ->
            obj.str("aweme_id", "item_id", "group_id").isNotBlank() && obj.obj("video") != null
        }.mapNotNull(::video)
            .filter { it.raw.int("aweme_type", "type") == 0 && it.playAddrUrls.isNotEmpty() }
            .distinctBy(VideoItem::id)
        return Page(videos, root.long("max_cursor", "cursor", "offset"), root.bool("has_more"), root.str("search_id"))
    }

    fun video(root: JsonObject): VideoItem? {
        root.obj("aweme_detail", "aweme_info")?.let { return video(it) }
        val id = root.str("aweme_id", "item_id", "group_id")
        val video = root.obj("video") ?: return null
        if (id.isBlank()) return null
        val author = author(root.obj("author", "user", "owner"))
        val stats = root.obj("statistics", "statistics_info")
        val width = video.int("width")
        val height = video.int("height")
        val cover = firstUrl(video.obj("cover", "origin_cover", "poster"))
        val dynamicCover = firstUrl(video.obj("dynamic_cover", "animated_cover"))
        val playAddrUrls = urlList(video.obj("play_addr"))
        val playAddr265Urls = urlList(video.obj("play_addr_265"))
        val sources = buildList {
            video.arr("bit_rate", "bitrate").objects().forEach { rate ->
                val address = rate.obj("play_addr") ?: rate.obj("play_addr_265") ?: rate.obj("download_addr")
                val urls = urlList(address)
                if (urls.isNotEmpty()) add(
                    VideoSource(
                        url = selectBestPlayUrl(urls),
                        quality = rate.str("gear_name", "quality_type", "format").ifBlank { "默认" },
                        codec = if (rate.bool("is_h265", "is_bytevc1") || rate.int("is_h265", "is_bytevc1") == 1) "h265" else "h264",
                        width = address?.int("width")?.takeIf { it > 0 } ?: width,
                        height = address?.int("height")?.takeIf { it > 0 } ?: height,
                        bitRate = rate.long("bit_rate", "bitrate"),
                        fps = rate.int("FPS", "fps"),
                        urls = urls
                    )
                )
            }
            if (isEmpty()) {
                listOf("play_addr", "play_addr_265", "download_addr").forEach { key ->
                    val urls = urlList(video.obj(key))
                    if (urls.isNotEmpty()) add(
                        VideoSource(
                            url = selectBestPlayUrl(urls),
                            quality = key,
                            codec = if (key.contains("265")) "h265" else "h264",
                            width = width,
                            height = height,
                            urls = urls
                        )
                    )
                }
            }
        }.distinctBy(VideoSource::url)
        val mixObj = root.obj("mix_info", "series_info")
        val mix = mixObj?.let {
            MixSummary(
                id = it.str("mix_id", "series_id", "id"),
                name = it.str("mix_name", "series_name", "name", "title"),
                currentEpisode = root.int("mix_order", "series_current_episode", "episode")
                    .takeIf { value -> value > 0 } ?: it.int("mix_order", "current_episode", "episode"),
                totalEpisodes = it.int("updated_to_episode", "total_episode", "video_count"),
                cover = firstUrl(it.obj("cover_url", "cover"))
            )
        }?.takeIf { it.id.isNotBlank() }
        return VideoItem(
            id = id,
            description = root.str("desc", "title", "caption"),
            author = author,
            cover = cover,
            dynamicCover = dynamicCover,
            sources = sources,
            playAddrUrls = playAddrUrls,
            playAddr265Urls = playAddr265Urls,
            width = width,
            height = height,
            durationMs = video.long("duration"),
            createTime = root.long("create_time"),
            stats = VideoStats(
                digg = stats?.long("digg_count", "like_count") ?: 0,
                comment = stats?.long("comment_count") ?: 0,
                collect = stats?.long("collect_count") ?: 0,
                share = stats?.long("share_count") ?: 0,
                play = stats?.long("play_count") ?: 0
            ),
            liked = root.bool("user_digged") || root.int("user_digged") == 1,
            collected = root.bool("collect_stat") || root.int("collect_stat") == 1,
            authenticationToken = root.str("authentication_token").ifBlank { video.str("authentication_token") },
            mix = mix,
            raw = root
        )
    }

    fun selectVideoSource(item: VideoItem, settings: AppSettings): VideoSource? {
        val candidates = when (settings.codecPreference) {
            CodecPreference.H265 -> item.sources.filter { it.codec == "h265" }
            CodecPreference.H264 -> item.sources.filter { it.codec == "h264" }
            CodecPreference.AUTO -> item.sources
        }
        val target = settings.videoResolution.targetHeight
        val urls = when (settings.videoResolution) {
            VideoResolution.AUTO ->
                if (settings.codecPreference == CodecPreference.H265) {
                    item.playAddr265Urls.ifEmpty { item.playAddrUrls }
                } else item.playAddrUrls
            VideoResolution.HIGHEST ->
                (candidates.maxByOrNull { it.width * it.height } ?: candidates.maxByOrNull { it.bitRate })
                    ?.urls?.ifEmpty { item.playAddrUrls }
            else ->
                candidates.firstOrNull { it.quality.contains(target.toString(), ignoreCase = true) }?.urls
                    ?: item.playAddrUrls
        }.orEmpty().filter(String::isNotBlank)
        if (urls.isEmpty()) return null
        return VideoSource(
            url = selectBestPlayUrl(urls),
            quality = settings.videoResolution.label,
            codec = if (settings.codecPreference == CodecPreference.H265) "h265" else "h264",
            urls = urls
        )
    }

    /** 2.1.37 VideoPlayUrlResolver.selectBestPlayUrl: signed CDN path first, API play URL next. */
    fun selectBestPlayUrl(urls: List<String>): String {
        if (urls.isEmpty()) return ""
        urls.firstOrNull { signedPathPattern.containsMatchIn(it) }?.let { return it }
        urls.firstOrNull { "douyin.com/aweme/v1/play" in it }?.let { return it }
        urls.firstOrNull { "signature=" in it && "douyinvod.com" in it }?.let { return it }
        return urls.last()
    }

    private val signedPathPattern = Regex("douyinvod\\.com/[0-9a-f]{32}/[0-9a-f]{8}/")

    fun author(value: JsonObject?): AuthorInfo {
        val obj = value ?: return AuthorInfo()
        val followStatus = obj.int("follow_status", "followStatus")
        return AuthorInfo(
            uid = obj.str("uid", "id_str", "id", "user_id"),
            secUid = obj.str("sec_uid", "sec_user_id"),
            nickname = obj.str("nickname", "nick_name", "name"),
            signature = obj.str("signature", "bio", "desc"),
            avatar = firstUrl(obj.obj("avatar_thumb", "avatar_medium", "avatar_larger", "avatar")),
            followingCount = obj.long("following_count"),
            followerCount = obj.long("follower_count"),
            totalFavorited = obj.long("total_favorited", "total_favorited_new"),
            awemeCount = obj.long("aweme_count", "works_count"),
            isFollowing = followStatus == 1 || followStatus == 2 || obj.bool("is_following")
        )
    }

    fun profile(root: JsonObject): AuthorInfo {
        root.obj("user", "user_info", "author")?.let { return author(it) }
        return collectObjects(root) { it.containsKey("nickname") && (it.containsKey("sec_uid") || it.containsKey("uid")) }
            .firstOrNull()?.let(::author) ?: AuthorInfo()
    }

    fun userPage(root: JsonObject): Page<AuthorInfo> {
        val users = collectObjects(root) {
            it.containsKey("nickname") && (it.containsKey("sec_uid") || it.containsKey("uid") || it.containsKey("id_str"))
        }.map(::author).filter { it.uid.isNotBlank() || it.secUid.isNotBlank() }.distinctBy { it.secUid.ifBlank { it.uid } }
        return Page(users, root.long("max_cursor", "cursor", "offset"), root.bool("has_more"))
    }

    fun comments(root: JsonObject): Page<CommentItem> {
        val candidates = collectObjects(root) { it.containsKey("cid") && it.containsKey("text") }
        val comments = candidates.mapNotNull(::comment).distinctBy(CommentItem::id)
        return Page(comments, root.long("cursor"), root.bool("has_more"))
    }

    private fun comment(obj: JsonObject): CommentItem? {
        val id = obj.str("cid", "comment_id")
        if (id.isBlank()) return null
        val images = collectObjects(obj.obj("image_list", "image_comment") ?: JsonObject(emptyMap())) {
            it.containsKey("url_list") || it.containsKey("url")
        }.mapNotNull(::firstUrl).filter(String::isNotBlank)
        val replies = obj.arr("reply_comment", "reply_list").objects().mapNotNull(::comment)
        return CommentItem(
            id = id,
            text = obj.str("text", "content"),
            user = author(obj.obj("user", "author")),
            diggCount = obj.long("digg_count"),
            replyCount = obj.long("reply_comment_total", "reply_count"),
            createTime = obj.long("create_time"),
            images = images,
            replies = replies
        )
    }

    fun danmaku(root: JsonObject): List<DanmakuItem> = collectObjects(root) {
        (it.containsKey("text") || it.containsKey("content")) &&
            (it.containsKey("time_offset") || it.containsKey("time") || it.containsKey("progress"))
    }.mapIndexed { index, obj ->
        DanmakuItem(
            id = obj.str("danmaku_id", "id").ifBlank { index.toString() },
            text = obj.str("text", "content"),
            timeMs = obj.long("time_offset", "time", "progress"),
            color = obj.long("color").takeIf { it != 0L } ?: 0xFFFFFFFF,
            user = author(obj.obj("user")),
            image = firstUrl(obj.obj("emoji", "emoji_url"))
        )
    }.filter { it.text.isNotBlank() }

    fun channels(root: JsonObject): List<ChannelItem> = collectObjects(root) {
        it.containsKey("ch_name") && (it.containsKey("tag_id") || it.containsKey("ch_id"))
    }.map {
        ChannelItem(
            id = it.str("tag_id", "ch_id", "id"),
            title = it.str("ch_name", "name", "title"),
            icon = firstUrl(it.obj("icon", "cover")),
            type = it.str("type")
        )
    }.filter { it.id.isNotBlank() && it.title.isNotBlank() }.distinctBy(ChannelItem::id)

    fun collections(root: JsonObject): Page<CollectionFolder> {
        val values = collectObjects(root) { it.containsKey("collect_id") || it.containsKey("mix_id") || it.containsKey("series_id") }
        val items = values.map {
            CollectionFolder(
                id = it.str("collect_id", "mix_id", "series_id", "id"),
                title = it.str("collect_name", "mix_name", "series_name", "name", "title"),
                cover = firstUrl(it.obj("cover", "cover_url")),
                count = it.int("collect_count", "video_count", "total_episode"),
                owner = author(it.obj("author", "user"))
            )
        }.filter { it.id.isNotBlank() }.distinctBy(CollectionFolder::id)
        return Page(items, root.long("cursor", "max_cursor"), root.bool("has_more"))
    }

    fun livePage(root: JsonObject): Page<LiveRoomItem> {
        val candidates = collectObjects(root) { obj ->
            val room = obj.obj("room") ?: obj
            (room.containsKey("stream_url") || room.containsKey("stream_data")) &&
                (room.containsKey("id_str") || room.containsKey("room_id"))
        }
        val rooms = candidates.mapNotNull { outer -> liveRoom(outer.obj("room") ?: outer, outer.str("web_rid")) }
            .distinctBy(LiveRoomItem::roomId)
        return Page(rooms, root.long("cursor", "offset", "max_time"), root.bool("has_more"))
    }

    fun liveRoom(root: JsonObject, outerWebRid: String = ""): LiveRoomItem? {
        root.obj("data", "room")?.takeIf { it.containsKey("stream_url") }?.let { return liveRoom(it, outerWebRid) }
        val id = root.str("id_str", "room_id", "id", "enter_room_id")
        if (id.isBlank()) return null
        val stream = root.obj("stream_url", "stream_data") ?: JsonObject(emptyMap())
        val owner = author(root.obj("owner", "user", "author"))
        val view = root.obj("room_view_stats", "stats")
        val cameras = root.arr("camera_infos", "camera_info").objects().mapIndexed { index, item ->
            LiveCamera(
                id = item.str("camera_id_str", "camera_id", "id").ifBlank { index.toString() },
                title = item.str("title", "name").ifBlank { "机位 ${index + 1}" },
                roomId = item.str("room_id"),
                webRid = item.str("web_rid")
            )
        }
        return LiveRoomItem(
            roomId = id,
            webRid = root.str("web_rid").ifBlank { outerWebRid },
            title = root.str("title", "room_title"),
            owner = owner,
            cover = firstUrl(root.obj("cover", "room_cover")),
            onlineText = view?.str("display_long", "display_middle", "display_short", "user_count_str").orEmpty()
                .ifBlank { root.str("user_count_str") },
            qualities = liveQualities(stream),
            cameras = cameras,
            status = root.int("status"),
            raw = root
        )
    }

    fun selectLiveQuality(room: LiveRoomItem, settings: AppSettings): LiveQuality? {
        var values = room.qualities.filter { it.playableUrl.isNotBlank() }
        values = when (settings.codecPreference) {
            CodecPreference.H264 -> values.filter { it.codec != "h265" }.ifEmpty { values }
            CodecPreference.H265 -> values.filter { it.codec == "h265" }.ifEmpty { values }
            CodecPreference.AUTO -> values
        }
        val sorted = values.sortedWith(compareByDescending<LiveQuality> { it.level }.thenByDescending { it.bitRate })
        if (sorted.isEmpty()) return null
        val last = sorted.lastIndex
        val index = when (settings.liveResolution) {
            LiveResolution.AUTO, LiveResolution.HIGHEST -> 0
            LiveResolution.HIGH -> (last / 3.0).toInt()
            LiveResolution.MEDIUM -> (last / 2.0).toInt()
            LiveResolution.LOW -> last
        }.coerceIn(0, last)
        return sorted[index]
    }

    private fun liveQualities(stream: JsonObject): List<LiveQuality> {
        val hls = stream.obj("hls_pull_url_map")?.stringMap().orEmpty().toMutableMap()
        val flv = stream.obj("flv_pull_url")?.stringMap().orEmpty().toMutableMap()
        stream.str("hls_pull_url").takeIf(String::isNotBlank)?.let { hls.putIfAbsent("DEFAULT", it) }
        val sdkPull = stream.obj("live_core_sdk_data")?.obj("pull_data")
        val streamData = sdkPull?.element("stream_data").embeddedJson()?.obj("data")
        val sdk = mutableListOf<LiveQuality>()
        streamData?.forEach { (key, value) ->
            val valueObj = value.asObject() ?: return@forEach
            val main = valueObj.obj("main") ?: valueObj
            val sdkParams = main.element("sdk_params").embeddedJson()
            sdk += LiveQuality(
                key = key,
                label = qualityLabel(key),
                hls = main.str("hls"),
                flv = main.str("flv"),
                level = qualityLevel(key),
                codec = sdkParams?.str("VCodec", "vcodec", "codec").orEmpty().let { if (it.contains("265") || it.contains("bytevc", true)) "h265" else "h264" },
                bitRate = sdkParams?.long("vbitrate", "bitrate") ?: 0
            )
        }
        val mapped = (hls.keys + flv.keys).distinct().map { key ->
            LiveQuality(
                key = key,
                label = qualityLabel(key),
                hls = hls[key].orEmpty(),
                flv = flv[key].orEmpty(),
                level = qualityLevel(key),
                codec = if (key.contains("HEVC", true)) "h265" else "h264"
            )
        }
        return (sdk + mapped).filter { it.playableUrl.isNotBlank() }
            .distinctBy { "${it.key}:${it.playableUrl}" }
            .sortedByDescending(LiveQuality::level)
    }

    fun search(root: JsonObject): SearchResults {
        val videos = videoPage(root).items
        val users = collectObjects(root) {
            (it.containsKey("user_info") || it.containsKey("nickname")) && !it.containsKey("video")
        }.map { author(it.obj("user_info") ?: it) }
            .filter { it.uid.isNotBlank() || it.secUid.isNotBlank() }
            .distinctBy { it.secUid.ifBlank { it.uid } }
        val lives = livePage(root).items
        return SearchResults(videos, users, lives, root.str("search_id"), root.bool("has_more"), root.long("cursor", "offset"))
    }

    fun hotWords(root: JsonObject): List<String> = collectObjects(root) {
        it.containsKey("word") || it.containsKey("sentence") || it.containsKey("hot_value")
    }.map { it.str("word", "sentence", "name", "keyword") }.filter(String::isNotBlank).distinct()

    fun suggestions(root: JsonObject): List<String> = collectObjects(root) {
        it.containsKey("content") || it.containsKey("word") || it.containsKey("query")
    }.map { it.str("content", "word", "query", "keyword") }.filter(String::isNotBlank).distinct()

    private fun qualityLabel(key: String): String = when (key.uppercase()) {
        "ORIGIN", "FULL_HD1" -> "原画"
        "UHD", "HD1" -> "超清"
        "HD", "SD2" -> "高清"
        "SD", "SD1" -> "标清"
        "MD" -> "流畅"
        "LD" -> "极速"
        "AO" -> "纯音频"
        "DEFAULT" -> "默认"
        else -> key
    }

    private fun qualityLevel(key: String): Int = when (key.uppercase()) {
        "ORIGIN", "FULL_HD1" -> 700
        "UHD" -> 600
        "HD", "HD1" -> 500
        "SD", "SD2" -> 400
        "MD", "SD1" -> 300
        "LD" -> 200
        "AO" -> 100
        else -> 250
    }
}

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray
internal fun JsonElement?.asPrimitive(): JsonPrimitive? = this as? JsonPrimitive

internal fun JsonObject.element(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeUnless { it is JsonNull } }

internal fun JsonObject.obj(vararg names: String): JsonObject? = element(*names).asObject()
internal fun JsonObject.arr(vararg names: String): JsonArray = element(*names).asArray() ?: JsonArray(emptyList())
internal fun JsonArray.objects(): List<JsonObject> = mapNotNull(JsonElement::asObject)

internal fun JsonObject.str(vararg names: String): String = names.firstNotNullOfOrNull { name ->
    val value = this[name] ?: return@firstNotNullOfOrNull null
    when (value) {
        is JsonPrimitive -> value.contentOrNull
        else -> null
    }?.takeUnless { it == "null" }
}.orEmpty()

internal fun JsonObject.long(vararg names: String): Long = names.firstNotNullOfOrNull { name ->
    val p = this[name].asPrimitive() ?: return@firstNotNullOfOrNull null
    p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.contentOrNull?.toLongOrNull()
} ?: 0L

internal fun JsonObject.int(vararg names: String): Int = names.firstNotNullOfOrNull { name ->
    val p = this[name].asPrimitive() ?: return@firstNotNullOfOrNull null
    p.intOrNull ?: p.longOrNull?.toInt() ?: p.contentOrNull?.toIntOrNull()
} ?: 0

internal fun JsonObject.bool(vararg names: String): Boolean = names.firstNotNullOfOrNull { name ->
    val p = this[name].asPrimitive() ?: return@firstNotNullOfOrNull null
    p.booleanOrNull ?: p.intOrNull?.let { it != 0 } ?: when (p.contentOrNull?.lowercase()) {
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> null
    }
} ?: false

internal fun JsonObject.stringMap(): Map<String, String> = mapNotNull { (key, value) ->
    value.asPrimitive()?.contentOrNull?.takeIf(String::isNotBlank)?.let { key to it }
}.toMap()

internal fun JsonElement?.embeddedJson(): JsonObject? = when (this) {
    is JsonObject -> this
    is JsonPrimitive -> contentOrNull?.takeIf { it.startsWith('{') }?.let {
        runCatching { DouyinJson.parse(it) }.getOrNull()
    }
    else -> null
}

internal fun firstUrl(obj: JsonObject?): String {
    if (obj == null) return ""
    obj.str("url", "uri").takeIf(String::isNotBlank)?.let { return it }
    val list = obj.arr("url_list", "urls")
    return list.firstNotNullOfOrNull { it.asPrimitive()?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()
}

internal fun urlList(obj: JsonObject?): List<String> {
    if (obj == null) return emptyList()
    val values = obj.arr("url_list", "urls").mapNotNull { it.asPrimitive()?.contentOrNull }.filter(String::isNotBlank)
    return if (values.isNotEmpty()) values else listOfNotNull(obj.str("url").takeIf(String::isNotBlank))
}

private fun collectObjects(root: JsonElement, predicate: (JsonObject) -> Boolean): List<JsonObject> {
    val output = ArrayList<JsonObject>()
    fun visit(element: JsonElement, depth: Int) {
        if (depth > 14 || output.size > 3_000) return
        when (element) {
            is JsonObject -> {
                if (predicate(element)) output += element
                element.values.forEach { visit(it, depth + 1) }
            }
            is JsonArray -> element.forEach { visit(it, depth + 1) }
            else -> Unit
        }
    }
    visit(root, 0)
    return output
}
