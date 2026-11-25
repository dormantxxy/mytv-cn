package com.mytv.cn.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.mytv.cn.MyApplication
import com.mytv.cn.data.AppSettings
import com.mytv.cn.data.AuthorInfo
import com.mytv.cn.data.ChannelItem
import com.mytv.cn.data.CollectionFolder
import com.mytv.cn.data.CommentItem
import com.mytv.cn.data.DanmakuItem
import com.mytv.cn.data.DouyinJson
import com.mytv.cn.data.LiveDanmakuItem
import com.mytv.cn.data.FeedKind
import com.mytv.cn.data.LiveRoomItem
import com.mytv.cn.data.ProfileTab
import com.mytv.cn.data.RootTab
import com.mytv.cn.data.SearchResults
import com.mytv.cn.data.VideoItem
import com.mytv.cn.network.QrLoginState
import com.mytv.cn.network.QrLoginStatus
import com.mytv.cn.player.PlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


data class MainUiState(
    val selectedTab: RootTab = RootTab.HOME,
    val feedKind: FeedKind = FeedKind.RECOMMEND,
    val videos: List<VideoItem> = emptyList(),
    val currentVideoIndex: Int = 0,
    val feedHasMore: Boolean = false,
    val featuredChannels: List<ChannelItem> = emptyList(),
    val selectedChannelId: String = "",
    val liveRooms: List<LiveRoomItem> = emptyList(),
    val liveFollowing: Boolean = false,
    val currentLive: LiveRoomItem? = null,
    val searchQuery: String = "",
    val hotWords: List<String> = emptyList(),
    val searchResults: SearchResults = SearchResults(),
    val userProfile: AuthorInfo? = null,
    val userProfileVideos: List<VideoItem> = emptyList(),
    val userProfileHasMore: Boolean = false,
    val commentReplies: Map<String, List<CommentItem>> = emptyMap(),
    val commentsHasMore: Boolean = false,
    val videoDanmaku: List<DanmakuItem> = emptyList(),
    val liveMessages: List<LiveDanmakuItem> = emptyList(),
    val profile: AuthorInfo? = null,
    val profileTab: ProfileTab = ProfileTab.WORKS,
    val profileVideos: List<VideoItem> = emptyList(),
    val profileUsers: List<AuthorInfo> = emptyList(),
    val profileCollections: List<CollectionFolder> = emptyList(),
    val comments: List<CommentItem> = emptyList(),
    val commentsOpen: Boolean = false,
    val commentsLoading: Boolean = false,
    val settingsOpen: Boolean = false,
    val qrLogin: QrLoginState = QrLoginState(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val message: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MyApplication
    private val mutable = MutableStateFlow(
        MainUiState(selectedTab = defaultRootTab(app.settings.state.value.defaultHomeScreen))
    )
    val uiState: StateFlow<MainUiState> = mutable.asStateFlow()
    val settings: StateFlow<AppSettings> = app.settings.state
    val playerState: StateFlow<PlayerState> = app.player.state
    val player: Player get() = app.player.player

    private var contentJob: Job? = null
    private var qrLoginJob: Job? = null
    private var feedPage = 1
    private var feedCursor = 0L
    private var userProfileCursor = 0L
    private var commentsCursor = 0L

    init {
        viewModelScope.launch {
            runCatching { app.api.initializeSession() }
            loadSelectedTab(force = true)
        }
        viewModelScope.launch {
            app.liveDanmaku.messages.collect { list ->
                if (mutable.value.currentLive != null) {
                    mutable.update { it.copy(liveMessages = list.filter { m -> m.type != "online" }.takeLast(30)) }
                }
            }
        }
    }

    fun selectRootTab(tab: RootTab) {
        if (mutable.value.selectedTab == tab) {
            refresh()
            return
        }
        mutable.update {
            it.copy(
                selectedTab = tab,
                commentsOpen = false,
                settingsOpen = false,
                currentLive = if (tab == RootTab.LIVE) it.currentLive else null,
                message = ""
            )
        }
        if (tab != RootTab.LIVE) app.player.pause()
        loadSelectedTab(force = false)
    }

    fun refresh() = loadSelectedTab(force = true)

    private fun loadSelectedTab(force: Boolean) {
        when (mutable.value.selectedTab) {
            RootTab.HOME -> loadFeed(replace = true)
            RootTab.FEATURED -> loadFeatured(force)
            RootTab.LIVE -> loadLive(mutable.value.liveFollowing)
            RootTab.SEARCH -> loadSearchHome(force)
            RootTab.ME -> loadProfile(mutable.value.profileTab, force)
        }
    }

    fun selectFeedKind(kind: FeedKind) {
        if (kind !in setOf(FeedKind.RECOMMEND, FeedKind.FOLLOWING, FeedKind.MOBILE)) return
        if (mutable.value.feedKind == kind && mutable.value.videos.isNotEmpty()) return
        mutable.update { it.copy(feedKind = kind, videos = emptyList(), currentVideoIndex = 0) }
        loadFeed(replace = true)
    }

    fun loadMoreFeed() {
        if (mutable.value.loadingMore || !mutable.value.feedHasMore) return
        loadFeed(replace = false)
    }

    private fun loadFeed(replace: Boolean) {
        contentJob?.cancel()
        val kind = mutable.value.feedKind
        if (replace) {
            feedPage = 1
            feedCursor = 0L
        }
        contentJob = viewModelScope.launch {
            mutable.update { it.copy(loading = replace, loadingMore = !replace, message = "") }
            try {
                val page = when (kind) {
                    FeedKind.FOLLOWING -> app.api.followingFeed(feedCursor)
                    FeedKind.MOBILE -> app.api.recommendation(feedPage, mobile = true)
                    else -> app.api.recommendation(feedPage)
                }
                if (kind != mutable.value.feedKind || mutable.value.selectedTab != RootTab.HOME) return@launch
                val merged = if (replace) page.items else (mutable.value.videos + page.items).distinctBy(VideoItem::id)
                feedCursor = page.cursor
                feedPage += 1
                mutable.update {
                    it.copy(
                        videos = merged,
                        currentVideoIndex = if (replace) 0 else it.currentVideoIndex,
                        feedHasMore = page.hasMore || page.items.isNotEmpty(),
                        loading = false,
                        loadingMore = false,
                        message = if (merged.isEmpty()) "暂无可播放内容" else ""
                    )
                }
                if (replace && merged.isNotEmpty()) selectVideo(0)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showError(error, replace)
            }
        }
    }

    private fun loadFeatured(force: Boolean) {
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, message = "") }
            try {
                val channels = if (force || mutable.value.featuredChannels.isEmpty()) {
                    app.api.featuredChannels()
                } else mutable.value.featuredChannels
                val selected = mutable.value.selectedChannelId.takeIf { id -> channels.any { it.id == id } }
                    ?: channels.firstOrNull()?.id.orEmpty()
                val videos = if (selected.isBlank()) emptyList() else app.api.featuredFeed(selected).items
                if (mutable.value.selectedTab != RootTab.FEATURED) return@launch
                mutable.update {
                    it.copy(
                        featuredChannels = channels,
                        selectedChannelId = selected,
                        videos = videos,
                        currentVideoIndex = 0,
                        feedHasMore = videos.isNotEmpty(),
                        loading = false,
                        message = if (channels.isEmpty()) "暂无精选频道" else ""
                    )
                }
                if (videos.isNotEmpty()) selectVideo(0)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showError(error, true)
            }
        }
    }

    fun selectFeaturedChannel(channel: ChannelItem) {
        if (channel.id == mutable.value.selectedChannelId) return
        mutable.update { it.copy(selectedChannelId = channel.id, videos = emptyList()) }
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, message = "") }
            try {
                val videos = app.api.featuredFeed(channel.id).items
                if (mutable.value.selectedChannelId != channel.id) return@launch
                mutable.update { it.copy(videos = videos, currentVideoIndex = 0, loading = false) }
                if (videos.isNotEmpty()) selectVideo(0)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showError(error, true)
            }
        }
    }

    fun selectVideo(index: Int) {
        val item = mutable.value.videos.getOrNull(index) ?: return
        mutable.update { it.copy(currentVideoIndex = index, message = "") }
        app.settings.markViewed(item.id)
        app.player.playVideo(item, settings.value, app.api.playbackHeaders())
        mutable.update { it.copy(videoDanmaku = emptyList()) }
        if (settings.value.videoDanmaku && item.durationMs > 0) {
            viewModelScope.launch {
                runCatching { app.api.videoDanmaku(item, 0, item.durationMs) }
                    .onSuccess { list ->
                        if (currentVideo()?.id == item.id && mutable.value.videoDanmaku.isEmpty()) {
                            mutable.update { it.copy(videoDanmaku = list) }
                        }
                    }
            }
        }
        viewModelScope.launch {
            app.api.writeHistory(item.author.uid, item.id)
            app.api.reportPlay(item.id)
        }
    }

    fun nextVideo() {
        val next = mutable.value.currentVideoIndex + 1
        if (next < mutable.value.videos.size) selectVideo(next) else loadMoreFeed()
    }

    fun previousVideo() {
        selectVideo((mutable.value.currentVideoIndex - 1).coerceAtLeast(0))
    }

    fun togglePlayback() = app.player.toggle()

    fun openVideo(item: VideoItem) {
        mutable.update {
            it.copy(
                selectedTab = RootTab.HOME,
                videos = listOf(item),
                currentVideoIndex = 0,
                feedHasMore = false,
                commentsOpen = false,
                settingsOpen = false
            )
        }
        selectVideo(0)
    }

    fun openVideoId(id: String) {
        if (id.isBlank()) return
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, message = "") }
            try {
                openVideo(app.api.videoDetail(id))
                mutable.update { it.copy(loading = false) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showError(error, true)
            }
        }
    }

    fun showComments() {
        val item = currentVideo() ?: return
        mutable.update {
            it.copy(commentsOpen = true, commentsLoading = true, comments = emptyList(), commentReplies = emptyMap(), commentsHasMore = false)
        }
        viewModelScope.launch {
            try {
                val page = app.api.comments(item.id)
                if (mutable.value.commentsOpen) {
                    mutable.update {
                        it.copy(comments = page.items, commentsLoading = false, commentsCursor = page.cursor, commentsHasMore = page.hasMore)
                    }
                }
            } catch (error: Throwable) {
                mutable.update { it.copy(commentsLoading = false, message = error.userMessage()) }
            }
        }
    }

    fun hideComments() = mutable.update { it.copy(commentsOpen = false) }

    fun toggleLike() {
        val item = currentVideo() ?: return
        val target = !item.liked
        replaceVideo(item.id) { it.copy(liked = target, stats = it.stats.copy(digg = (it.stats.digg + if (target) 1 else -1).coerceAtLeast(0))) }
        viewModelScope.launch {
            runCatching { app.api.setLiked(item.id, target) }.onFailure { error ->
                replaceVideo(item.id) { it.copy(liked = item.liked, stats = item.stats) }
                mutable.update { it.copy(message = error.userMessage()) }
            }
        }
    }

    fun toggleCollected() {
        val item = currentVideo() ?: return
        val target = !item.collected
        replaceVideo(item.id) { it.copy(collected = target) }
        viewModelScope.launch {
            runCatching { app.api.setCollected(item.id, target) }.onFailure { error ->
                replaceVideo(item.id) { it.copy(collected = item.collected) }
                mutable.update { it.copy(message = error.userMessage()) }
            }
        }
    }

    private fun replaceVideo(id: String, transform: (VideoItem) -> VideoItem) {
        mutable.update { state ->
            state.copy(
                videos = state.videos.map { if (it.id == id) transform(it) else it },
                profileVideos = state.profileVideos.map { if (it.id == id) transform(it) else it },
                searchResults = state.searchResults.copy(
                    videos = state.searchResults.videos.map { if (it.id == id) transform(it) else it }
                )
            )
        }
    }

    fun loadLive(following: Boolean) {
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, liveFollowing = following, currentLive = null, message = "") }
            app.player.pause()
            try {
                val rooms = if (following) app.api.liveFollowing().items else app.api.liveRecommended().items
                if (mutable.value.selectedTab != RootTab.LIVE || mutable.value.liveFollowing != following) return@launch
                mutable.update {
                    it.copy(liveRooms = rooms, loading = false, message = if (rooms.isEmpty()) "暂无直播" else "")
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showError(error, true)
            }
        }
    }

    fun openLive(item: LiveRoomItem) {
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, message = "") }
            try {
                val room = when {
                    item.qualities.isNotEmpty() -> item
                    item.webRid.isNotBlank() -> app.api.enterLive(item.webRid, item.roomId)
                    else -> app.api.liveInfo(item.roomId)
                }
                val quality = DouyinJson.selectLiveQuality(room, settings.value)
                    ?: error("直播流不可用")
                app.player.playLive(room, quality, app.api.playbackHeaders(live = true))
                app.liveDanmaku.connect(room)
                mutable.update { it.copy(currentLive = room, loading = false, selectedTab = RootTab.LIVE, liveMessages = emptyList()) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showError(error, true)
            }
        }
    }

    fun closeLive() {
        app.player.pause()
        app.liveDanmaku.disconnect()
        mutable.update { it.copy(currentLive = null, liveMessages = emptyList()) }
    }

    fun updateSearchQuery(value: String) {
        mutable.update { it.copy(searchQuery = value.take(100)) }
    }

    private fun loadSearchHome(force: Boolean) {
        if (!force && mutable.value.hotWords.isNotEmpty()) return
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, message = "") }
            try {
                val words = app.api.hotWords()
                if (mutable.value.selectedTab == RootTab.SEARCH) {
                    mutable.update { it.copy(hotWords = words, loading = false) }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showError(error, true)
            }
        }
    }

    fun submitSearch(value: String = mutable.value.searchQuery) {
        val keyword = value.trim()
        if (keyword.isEmpty()) return
        updateSearchQuery(keyword)
        app.settings.addSearchHistory(keyword)
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, message = "") }
            try {
                val result = app.api.search(keyword)
                if (mutable.value.searchQuery == keyword) {
                    mutable.update { it.copy(searchResults = result, loading = false) }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showError(error, true)
            }
        }
    }

    fun selectProfileTab(tab: ProfileTab) {
        if (mutable.value.profileTab == tab &&
            (mutable.value.profileVideos.isNotEmpty() || mutable.value.profileUsers.isNotEmpty() || mutable.value.profileCollections.isNotEmpty())
        ) return
        mutable.update { it.copy(profileTab = tab) }
        loadProfile(tab, force = true)
    }

    private fun loadProfile(tab: ProfileTab, force: Boolean) {
        if (!settings.value.loggedIn) {
            mutable.update {
                it.copy(
                    loading = false,
                    profile = null,
                    profileVideos = emptyList(),
                    profileUsers = emptyList(),
                    profileCollections = emptyList()
                )
            }
            return
        }
        if (!force && mutable.value.profile != null && mutable.value.profileTab == tab &&
            (mutable.value.profileVideos.isNotEmpty() || mutable.value.profileUsers.isNotEmpty() || mutable.value.profileCollections.isNotEmpty())
        ) return
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, message = "") }
            try {
                val profile = mutable.value.profile ?: app.api.selfProfile()
                var videos = emptyList<VideoItem>()
                var users = emptyList<AuthorInfo>()
                var collections = emptyList<CollectionFolder>()
                when (tab) {
                    ProfileTab.WORKS -> videos = app.api.userPosts(profile.secUid).items
                    ProfileTab.LIKES -> videos = app.api.likedVideos(profile.secUid).items
                    ProfileTab.COLLECTIONS -> videos = app.api.collectedVideos().items
                    ProfileTab.WATCH_LATER -> videos = app.api.watchLater().items
                    ProfileTab.HISTORY -> videos = app.api.history().items
                    ProfileTab.MIXES -> collections = app.api.seriesList(profile.secUid).items
                    ProfileTab.FOLLOWERS -> users = app.api.followers(profile.uid, profile.secUid).items
                    ProfileTab.FOLLOWING -> users = app.api.following(profile.uid, profile.secUid).items
                }
                if (mutable.value.selectedTab == RootTab.ME && mutable.value.profileTab == tab) {
                    mutable.update {
                        it.copy(
                            profile = profile,
                            profileVideos = videos,
                            profileUsers = users,
                            profileCollections = collections,
                            loading = false
                        )
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showError(error, true)
            }
        }
    }

    fun showSettings() = mutable.update { it.copy(settingsOpen = true) }
    fun hideSettings() = mutable.update { it.copy(settingsOpen = false) }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = app.settings.update(transform)

    fun setCookie(cookie: String) {
        app.settings.setCookie(cookie)
        mutable.update { it.copy(message = "Cookie 已更新") }
    }

    fun startQrLogin() {
        if (mutable.value.qrLogin.status in setOf(QrLoginStatus.STARTING, QrLoginStatus.WAITING_SCAN, QrLoginStatus.SCANNED)) return
        qrLoginJob = viewModelScope.launch {
            runCatching {
                app.qrLogin.login { next -> mutable.update { it.copy(qrLogin = next) } }
            }.onSuccess {
                mutable.update { it.copy(message = "登录成功") }
                if (mutable.value.selectedTab == RootTab.ME) loadProfile(ProfileTab.WORKS, force = true)
            }.onFailure { error ->
                if (mutable.value.qrLogin.status != QrLoginStatus.EXPIRED) {
                    mutable.update {
                        it.copy(qrLogin = QrLoginState(QrLoginStatus.FAILED, error.userMessage()))
                    }
                }
            }
        }
    }

    fun closeQrLogin() {
        qrLoginJob?.cancel()
        qrLoginJob = null
        mutable.update { it.copy(qrLogin = QrLoginState()) }
    }

    fun loadMoreComments() {
        val item = currentVideo() ?: return
        val state = mutable.value
        if (!state.commentsOpen || state.commentsLoading || !state.commentsHasMore) return
        viewModelScope.launch {
            try {
                val page = app.api.comments(item.id, state.commentsCursor)
                if (mutable.value.commentsOpen) {
                    mutable.update {
                        it.copy(
                            comments = (it.comments + page.items).distinctBy(CommentItem::id),
                            commentsCursor = page.cursor,
                            commentsHasMore = page.hasMore
                        )
                    }
                }
            } catch (error: Throwable) {
                mutable.update { it.copy(message = error.userMessage()) }
            }
        }
    }

    fun toggleCommentReplies(comment: CommentItem) {
        val item = currentVideo() ?: return
        if (mutable.value.commentReplies.containsKey(comment.id)) {
            mutable.update { it.copy(commentReplies = it.commentReplies - comment.id) }
            return
        }
        viewModelScope.launch {
            try {
                val replies = app.api.commentReplies(item.id, comment.id).items
                mutable.update { it.copy(commentReplies = it.commentReplies + (comment.id to replies)) }
            } catch (error: Throwable) {
                mutable.update { it.copy(message = error.userMessage()) }
            }
        }
    }

    fun openUserProfile(author: AuthorInfo) {
        if (author.secUid.isBlank()) {
            mutable.update { it.copy(message = "该用户主页暂不可用") }
            return
        }
        mutable.update {
            it.copy(
                userProfile = author,
                userProfileVideos = emptyList(),
                userProfileHasMore = false,
                commentsOpen = false,
                settingsOpen = false
            )
        }
        userProfileCursor = 0L
        viewModelScope.launch {
            try {
                val profile = if (author.nickname.isBlank()) app.api.userProfile(author.secUid) else author
                val page = app.api.userPosts(profile.secUid)
                userProfileCursor = page.cursor
                if (mutable.value.userProfile?.secUid == profile.secUid || mutable.value.userProfile?.secUid.isNullOrBlank()) {
                    mutable.update {
                        it.copy(userProfile = profile, userProfileVideos = page.items, userProfileHasMore = page.hasMore)
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(message = error.userMessage()) }
            }
        }
    }

    fun loadMoreUserPosts() {
        val profile = mutable.value.userProfile ?: return
        if (mutable.value.loadingMore || !mutable.value.userProfileHasMore) return
        viewModelScope.launch {
            mutable.update { it.copy(loadingMore = true) }
            try {
                val page = app.api.userPosts(profile.secUid, userProfileCursor)
                userProfileCursor = page.cursor
                if (mutable.value.userProfile?.secUid == profile.secUid) {
                    mutable.update {
                        it.copy(
                            userProfileVideos = (it.userProfileVideos + page.items).distinctBy(VideoItem::id),
                            userProfileHasMore = page.hasMore,
                            loadingMore = false
                        )
                    }
                } else {
                    mutable.update { it.copy(loadingMore = false) }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(loadingMore = false, message = error.userMessage()) }
            }
        }
    }

    fun closeUserProfile() = mutable.update {
        it.copy(userProfile = null, userProfileVideos = emptyList(), userProfileHasMore = false)
    }

    fun dismissMessage() = mutable.update { it.copy(message = "") }

    fun handleBack(): Boolean {
        val state = mutable.value
        return when {
            state.settingsOpen -> {
                hideSettings(); true
            }
            state.qrLogin.status != QrLoginStatus.IDLE -> {
                closeQrLogin(); true
            }
            state.userProfile != null -> {
                closeUserProfile(); true
            }
            state.commentsOpen -> {
                hideComments(); true
            }
            state.currentLive != null -> {
                closeLive(); true
            }
            state.selectedTab != RootTab.HOME -> {
                selectRootTab(RootTab.HOME); true
            }
            else -> false
        }
    }

    private fun currentVideo(): VideoItem? = mutable.value.videos.getOrNull(mutable.value.currentVideoIndex)

    private fun showError(error: Throwable, replace: Boolean) {
        mutable.update {
            it.copy(
                loading = false,
                loadingMore = false,
                videos = if (replace && it.selectedTab in setOf(RootTab.HOME, RootTab.FEATURED)) emptyList() else it.videos,
                message = error.userMessage()
            )
        }
    }

    companion object {
        private fun defaultRootTab(value: Int): RootTab = when (value) {
            1 -> RootTab.FEATURED
            2 -> RootTab.LIVE
            3 -> RootTab.ME
            else -> RootTab.HOME
        }
    }
}

private fun Throwable.userMessage(): String = message?.takeIf(String::isNotBlank) ?: "操作失败，请稍后重试"
