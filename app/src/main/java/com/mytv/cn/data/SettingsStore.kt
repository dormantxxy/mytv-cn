package com.mytv.cn.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

private const val MAIN_ACCOUNT = "main"

/** All values observed in myDV 2.1.37's SettingsManager, with the same defaults. */
data class AppSettings(
    val accounts: List<AccountProfile> = emptyList(),
    val currentAccountId: String = MAIN_ACCOUNT,
    val videoResolution: VideoResolution = VideoResolution.AUTO,
    val liveResolution: LiveResolution = LiveResolution.HIGHEST,
    val codecPreference: CodecPreference = CodecPreference.AUTO,
    val separateVideoFetch: Boolean = true,
    val videoStartPrefetch: Boolean = false,
    val mixPlaylistPanel: Boolean = true,
    val openOwnProfileOnHistory: Boolean = false,
    val cleanPause: Boolean = false,
    val videoLoadTimeoutSeconds: Int = 10,
    val videoDanmaku: Boolean = true,
    val liveDanmaku: Boolean = true,
    val danmakuEmoji: Boolean = true,
    val danmakuStyle: DanmakuStyle = DanmakuStyle.SCROLL,
    val danmakuOpacity: Float = 1f,
    val danmakuAreaPercent: Int = 100,
    val danmakuVerticalSpacing: Float = 1f,
    val danmakuHorizontalSpacing: Float = 1f,
    val danmakuFontSize: Float = 2f,
    val danmakuSpeed: Float = 1f,
    val danmakuBlockEnabled: Boolean = false,
    val danmakuBlockWords: List<String> = emptyList(),
    val danmakuFilterPackage: Boolean = true,
    val filterMobileVideo: Boolean = false,
    val pipEnabled: Boolean = false,
    val reverseNavigation: Boolean = false,
    val reversePlaylistInProfile: Boolean = false,
    val disableDoubleBackExit: Boolean = false,
    val defaultHomeScreen: Int = 0,
    val showDetailsOnStart: Boolean = true,
    val featuredAutoPlay: Boolean = true,
    val playbackEndAction: PlaybackEndAction = PlaybackEndAction.AUTO_NEXT,
    val clockDisplayMode: ClockDisplayMode = ClockDisplayMode.NEVER,
    val playerProgressBar: Boolean = false,
    val playbackSpeed: Float = 1f,
    val feedHistoryCapacity: Int = 100,
    val audioBalance: AudioBalance = AudioBalance.OFF,
    val playerSurface: PlayerSurface = PlayerSurface.SURFACE,
    val surfaceBufferAlign: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val colorSource: ColorSource = if (Build.VERSION.SDK_INT >= 31) ColorSource.CONTENT else ColorSource.BRAND,
    val colorStyle: ColorStyle = ColorStyle.BY_SCENE,
    val colorContrast: ColorContrast = ColorContrast.STANDARD,
    val ambientBackdrop: Boolean = true,
    val colorTransition: Boolean = true,
    val searchHistory: List<String> = emptyList(),
    val viewedVideoIds: List<String> = emptyList()
) {
    val currentAccount: AccountProfile?
        get() = accounts.firstOrNull { it.id == currentAccountId } ?: accounts.firstOrNull()
    val cookie: String get() = currentAccount?.cookie.orEmpty()
    val interactionSecurity: InteractionSecurity get() = currentAccount?.security ?: InteractionSecurity()
    val loggedIn: Boolean
        get() = cookieMap().let { it["sessionid"].isNullOrBlank().not() || it["sessionid_ss"].isNullOrBlank().not() }

    fun cookieMap(): LinkedHashMap<String, String> = parseCookie(cookie)
}

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("douyin_auth", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = mutable.asStateFlow()

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(mutable.value).normalize()
        persist(next)
        mutable.value = next
    }

    fun setCookie(raw: String) {
        val normalized = raw.trim().replace('\n', ';')
        update { current ->
            val accounts = current.accounts.ensureMain().map {
                if (it.id == current.currentAccountId) it.copy(cookie = normalized) else it
            }
            current.copy(accounts = accounts)
        }
    }

    fun mergeSetCookies(values: List<String>) {
        if (values.isEmpty()) return
        val merged = mutable.value.cookieMap()
        values.forEach { header ->
            val pair = header.substringBefore(';')
            val split = pair.indexOf('=')
            if (split > 0) {
                val key = pair.substring(0, split).trim()
                val value = pair.substring(split + 1).trim()
                if (value.isEmpty()) merged.remove(key) else merged[key] = value
            }
        }
        setCookie(merged.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }

    fun setInteractionSecurity(security: InteractionSecurity) {
        update { current ->
            current.copy(accounts = current.accounts.ensureMain().map {
                if (it.id == current.currentAccountId) it.copy(security = security) else it
            })
        }
    }

    fun saveAccount(account: AccountProfile) {
        update { current ->
            val list = current.accounts.ensureMain().toMutableList()
            val index = list.indexOfFirst { it.id == account.id }
            if (index >= 0) list[index] = account else list += account
            current.copy(accounts = list, currentAccountId = account.id)
        }
    }

    fun switchAccount(id: String): Boolean {
        if (mutable.value.accounts.none { it.id == id }) return false
        update { it.copy(currentAccountId = id) }
        return true
    }

    fun removeAccount(id: String) {
        if (id == MAIN_ACCOUNT) return
        update { current ->
            val list = current.accounts.filterNot { it.id == id }.ensureMain()
            current.copy(
                accounts = list,
                currentAccountId = if (current.currentAccountId == id) MAIN_ACCOUNT else current.currentAccountId
            )
        }
    }

    fun protectAccount(id: String, pin: String) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes).toHex()
        val hash = pinHash(pin, salt)
        update { current -> current.copy(accounts = current.accounts.map {
            if (it.id == id) it.copy(pinSalt = salt, pinHash = hash) else it
        }) }
    }

    fun clearPin(id: String) = update { current -> current.copy(accounts = current.accounts.map {
        if (it.id == id) it.copy(pinSalt = "", pinHash = "") else it
    }) }

    fun verifyPin(id: String, pin: String): Boolean {
        val account = mutable.value.accounts.firstOrNull { it.id == id } ?: return false
        return !account.pinProtected || pinHash(pin, account.pinSalt) == account.pinHash
    }

    fun addSearchHistory(query: String) {
        val value = query.trim()
        if (value.isEmpty()) return
        update { it.copy(searchHistory = (listOf(value) + it.searchHistory.filterNot(value::equals)).take(20)) }
    }

    fun clearSearchHistory() = update { it.copy(searchHistory = emptyList()) }

    fun markViewed(id: String) {
        if (id.isBlank()) return
        update { current -> current.copy(
            viewedVideoIds = (current.viewedVideoIds.filterNot(id::equals) + id)
                .takeLast(current.feedHistoryCapacity)
        ) }
    }

    fun clearViewed() = update { it.copy(viewedVideoIds = emptyList()) }

    fun getOrCreateNumericId(key: String): String {
        prefs.getString(key, null)?.takeIf { it.length == 16 && it.all(Char::isDigit) }?.let { return it }
        val value = ((SecureRandom().nextLong().ushr(1) % 9_000_000_000_000_000L) + 1_000_000_000_000_000L).toString()
        prefs.edit().putString(key, value).apply()
        return value
    }

    private fun load(): AppSettings {
        val accounts = runCatching {
            prefs.getString("accounts_list", null)?.let {
                json.decodeFromString(ListSerializer(AccountProfile.serializer()), it)
            }
        }.getOrNull().orEmpty().toMutableList()

        if (accounts.isEmpty()) {
            accounts += AccountProfile(
                id = MAIN_ACCOUNT,
                name = "默认配置",
                cookie = prefs.getString("cookie_string", "").orEmpty(),
                security = InteractionSecurity(
                    rawPayload = prefs.getString("interaction_security_payload", "").orEmpty(),
                    ticket = prefs.getString("interaction_ticket", "").orEmpty(),
                    tsSign = prefs.getString("interaction_ts_sign", "").orEmpty(),
                    privateKey = prefs.getString("interaction_private_key", "").orEmpty(),
                    serverCertificate = prefs.getString("interaction_server_cert", "").orEmpty()
                ),
                userId = prefs.getString("self_user_id", "").orEmpty()
            )
        } else if (accounts.none { it.id == MAIN_ACCOUNT }) {
            accounts.add(0, AccountProfile(MAIN_ACCOUNT, "默认配置"))
        }

        fun <E : Enum<E>> enumByKey(values: Array<E>, key: String?, fallback: E, selector: (E) -> String): E =
            values.firstOrNull { selector(it) == key } ?: fallback
        fun csv(key: String): List<String> = prefs.getString(key, "").orEmpty().split(',').filter(String::isNotBlank)
        fun jsonStrings(key: String): List<String> = runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), prefs.getString(key, "[]").orEmpty())
        }.getOrElse { emptyList() }

        val currentId = prefs.getString("current_account_id", MAIN_ACCOUNT).orEmpty()
            .takeIf { id -> accounts.any { it.id == id } } ?: MAIN_ACCOUNT
        val oldDanmaku = prefs.getBoolean("danmaku_enabled", true)
        return AppSettings(
            accounts = accounts,
            currentAccountId = currentId,
            videoResolution = enumByKey(VideoResolution.entries.toTypedArray(), prefs.getString("video_resolution", "auto"), VideoResolution.AUTO) { it.key },
            liveResolution = enumByKey(LiveResolution.entries.toTypedArray(), prefs.getString("live_video_resolution", "highest"), LiveResolution.HIGHEST) { it.key },
            codecPreference = enumByKey(CodecPreference.entries.toTypedArray(), prefs.getString("video_codec_preference", "auto"), CodecPreference.AUTO) { it.key },
            separateVideoFetch = prefs.getBoolean("separate_video_fetch", true),
            videoStartPrefetch = prefs.getBoolean("video_start_prefetch", false),
            mixPlaylistPanel = prefs.getBoolean("mix_playlist_panel_enabled", true),
            openOwnProfileOnHistory = prefs.getBoolean("open_own_profile_on_history_tab", false),
            cleanPause = prefs.getBoolean("clean_pause", false),
            videoLoadTimeoutSeconds = prefs.getInt("video_load_timeout_seconds", 10).takeIf { it in setOf(5, 10, 15, 30, 60) } ?: 10,
            videoDanmaku = if (prefs.contains("video_danmaku_enabled")) prefs.getBoolean("video_danmaku_enabled", true) else oldDanmaku,
            liveDanmaku = if (prefs.contains("live_danmaku_enabled")) prefs.getBoolean("live_danmaku_enabled", true) else oldDanmaku,
            danmakuEmoji = prefs.getBoolean("danmaku_emoji_enabled", true),
            danmakuStyle = enumByKey(DanmakuStyle.entries.toTypedArray(), prefs.getString("live_danmaku_style", "scroll"), DanmakuStyle.SCROLL) { it.key },
            danmakuOpacity = prefs.getFloat("danmaku_opacity", 1f).coerceIn(.01f, 1f),
            danmakuAreaPercent = if (prefs.contains("danmaku_area_percent")) prefs.getInt("danmaku_area_percent", 100).coerceIn(1, 100) else (prefs.getFloat("danmaku_area", 1f) * 100).toInt().coerceIn(1, 100),
            danmakuVerticalSpacing = prefs.getFloat("danmaku_vertical_spacing", 1f).coerceIn(.5f, 2f),
            danmakuHorizontalSpacing = prefs.getFloat("danmaku_horizontal_spacing", 1f).coerceIn(.5f, 2f),
            danmakuFontSize = prefs.getFloat("danmaku_font_size", 2f),
            danmakuSpeed = prefs.getFloat("danmaku_speed", 1f),
            danmakuBlockEnabled = prefs.getBoolean("danmaku_block_enabled", false),
            danmakuBlockWords = jsonStrings("danmaku_block_words"),
            danmakuFilterPackage = prefs.getBoolean("danmaku_filter_package", true),
            filterMobileVideo = prefs.getBoolean("filter_mobile_video", false),
            pipEnabled = prefs.getBoolean("pip_enabled", false),
            reverseNavigation = prefs.getBoolean("reverse_navigation", false),
            reversePlaylistInProfile = prefs.getBoolean("reverse_playlist_in_profile", false),
            disableDoubleBackExit = prefs.getBoolean("disable_double_back_exit", false),
            defaultHomeScreen = prefs.getInt("default_home_screen", 0).coerceIn(0, 3),
            showDetailsOnStart = prefs.getBoolean("show_details_on_start", true),
            featuredAutoPlay = prefs.getBoolean("featured_auto_play", true),
            playbackEndAction = enumByKey(PlaybackEndAction.entries.toTypedArray(), prefs.getString("playback_completion_action", if (prefs.getBoolean("auto_play_next_video", true)) "auto_next" else "pause"), PlaybackEndAction.AUTO_NEXT) { it.key },
            clockDisplayMode = enumByKey(ClockDisplayMode.entries.toTypedArray(), prefs.getString("playback_clock_display_mode", "never"), ClockDisplayMode.NEVER) { it.key },
            playerProgressBar = prefs.getBoolean("player_progress_bar", false),
            playbackSpeed = prefs.getFloat("playback_speed", 1f).takeIf { it in setOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f, 3f) } ?: 1f,
            feedHistoryCapacity = prefs.getInt("feed_history_capacity", 100).takeIf { it in setOf(50, 100, 200, 500, 1000) } ?: 100,
            audioBalance = enumByKey(AudioBalance.entries.toTypedArray(), prefs.getString("audio_balance_level", "off"), AudioBalance.OFF) { it.key },
            playerSurface = enumByKey(PlayerSurface.entries.toTypedArray(), prefs.getString("player_surface_type", "surface_view"), PlayerSurface.SURFACE) { it.key },
            surfaceBufferAlign = prefs.getBoolean("surface_buffer_align", false),
            themeMode = ThemeMode.entries.firstOrNull { it.value == prefs.getInt("theme_mode", 0) } ?: ThemeMode.AUTO,
            colorSource = enumByKey(ColorSource.entries.toTypedArray(), prefs.getString("color_source", null), if (Build.VERSION.SDK_INT >= 31) ColorSource.CONTENT else ColorSource.BRAND) { it.key },
            colorStyle = enumByKey(ColorStyle.entries.toTypedArray(), prefs.getString("color_style", "by_scene"), ColorStyle.BY_SCENE) { it.key },
            colorContrast = enumByKey(ColorContrast.entries.toTypedArray(), prefs.getString("color_contrast", "standard"), ColorContrast.STANDARD) { it.key },
            ambientBackdrop = prefs.getBoolean("ambient_backdrop", true),
            colorTransition = prefs.getString("color_transition", "auto") != "off",
            searchHistory = jsonStrings("history"),
            viewedVideoIds = csv("viewed_video_ids")
        ).normalize()
    }

    private fun persist(value: AppSettings) {
        val accountJson = json.encodeToString(ListSerializer(AccountProfile.serializer()), value.accounts)
        prefs.edit()
            .putString("accounts_list", accountJson)
            .putString("current_account_id", value.currentAccountId)
            .putString("cookie_string", value.cookie)
            .putString("interaction_security_payload", value.interactionSecurity.rawPayload)
            .putString("interaction_ticket", value.interactionSecurity.ticket)
            .putString("interaction_ts_sign", value.interactionSecurity.tsSign)
            .putString("interaction_private_key", value.interactionSecurity.privateKey)
            .putString("interaction_server_cert", value.interactionSecurity.serverCertificate)
            .putString("video_resolution", value.videoResolution.key)
            .putString("live_video_resolution", value.liveResolution.key)
            .putString("video_codec_preference", value.codecPreference.key)
            .putBoolean("separate_video_fetch", value.separateVideoFetch)
            .putBoolean("video_start_prefetch", value.videoStartPrefetch)
            .putBoolean("mix_playlist_panel_enabled", value.mixPlaylistPanel)
            .putBoolean("open_own_profile_on_history_tab", value.openOwnProfileOnHistory)
            .putBoolean("clean_pause", value.cleanPause)
            .putInt("video_load_timeout_seconds", value.videoLoadTimeoutSeconds)
            .putBoolean("video_danmaku_enabled", value.videoDanmaku)
            .putBoolean("live_danmaku_enabled", value.liveDanmaku)
            .putBoolean("danmaku_emoji_enabled", value.danmakuEmoji)
            .putString("live_danmaku_style", value.danmakuStyle.key)
            .putFloat("danmaku_opacity", value.danmakuOpacity)
            .putInt("danmaku_area_percent", value.danmakuAreaPercent)
            .putFloat("danmaku_area", value.danmakuAreaPercent / 100f)
            .putFloat("danmaku_vertical_spacing", value.danmakuVerticalSpacing)
            .putFloat("danmaku_horizontal_spacing", value.danmakuHorizontalSpacing)
            .putFloat("danmaku_font_size", value.danmakuFontSize)
            .putFloat("danmaku_speed", value.danmakuSpeed)
            .putBoolean("danmaku_block_enabled", value.danmakuBlockEnabled)
            .putString("danmaku_block_words", json.encodeToString(ListSerializer(String.serializer()), value.danmakuBlockWords))
            .putBoolean("danmaku_filter_package", value.danmakuFilterPackage)
            .putBoolean("filter_mobile_video", value.filterMobileVideo)
            .putBoolean("pip_enabled", value.pipEnabled)
            .putBoolean("reverse_navigation", value.reverseNavigation)
            .putBoolean("reverse_playlist_in_profile", value.reversePlaylistInProfile)
            .putBoolean("disable_double_back_exit", value.disableDoubleBackExit)
            .putInt("default_home_screen", value.defaultHomeScreen)
            .putBoolean("show_details_on_start", value.showDetailsOnStart)
            .putBoolean("featured_auto_play", value.featuredAutoPlay)
            .putString("playback_completion_action", value.playbackEndAction.key)
            .putBoolean("auto_play_next_video", value.playbackEndAction == PlaybackEndAction.AUTO_NEXT)
            .putString("playback_clock_display_mode", value.clockDisplayMode.key)
            .putBoolean("player_progress_bar", value.playerProgressBar)
            .putFloat("playback_speed", value.playbackSpeed)
            .putInt("feed_history_capacity", value.feedHistoryCapacity)
            .putString("audio_balance_level", value.audioBalance.key)
            .putString("player_surface_type", value.playerSurface.key)
            .putBoolean("surface_buffer_align", value.surfaceBufferAlign)
            .putInt("theme_mode", value.themeMode.value)
            .putString("color_source", value.colorSource.key)
            .putString("color_style", value.colorStyle.key)
            .putString("color_contrast", value.colorContrast.key)
            .putBoolean("ambient_backdrop", value.ambientBackdrop)
            .putString("color_transition", if (value.colorTransition) "auto" else "off")
            .putString("history", json.encodeToString(ListSerializer(String.serializer()), value.searchHistory))
            .putString("viewed_video_ids", value.viewedVideoIds.joinToString(","))
            .apply()
    }

    private fun AppSettings.normalize(): AppSettings {
        val normalizedAccounts = accounts.ensureMain()
        return copy(
            accounts = normalizedAccounts,
            currentAccountId = currentAccountId.takeIf { id -> normalizedAccounts.any { it.id == id } } ?: MAIN_ACCOUNT,
            danmakuOpacity = danmakuOpacity.coerceIn(.01f, 1f),
            danmakuAreaPercent = danmakuAreaPercent.coerceIn(1, 100),
            danmakuVerticalSpacing = danmakuVerticalSpacing.coerceIn(.5f, 2f),
            danmakuHorizontalSpacing = danmakuHorizontalSpacing.coerceIn(.5f, 2f),
            viewedVideoIds = viewedVideoIds.distinct().takeLast(feedHistoryCapacity),
            searchHistory = searchHistory.distinct().take(20)
        )
    }

    private fun pinHash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).toHex()
}

fun parseCookie(raw: String): LinkedHashMap<String, String> {
    val result = LinkedHashMap<String, String>()
    raw.replace('\n', ';').split(';').forEach { part ->
        val split = part.indexOf('=')
        if (split > 0) {
            val key = part.substring(0, split).trim()
            val value = part.substring(split + 1).trim()
            if (key.isNotEmpty()) result[key] = value
        }
    }
    return result
}

private fun List<AccountProfile>.ensureMain(): List<AccountProfile> = when {
    any { it.id == MAIN_ACCOUNT } -> this
    else -> listOf(AccountProfile(MAIN_ACCOUNT, "默认配置")) + this
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
