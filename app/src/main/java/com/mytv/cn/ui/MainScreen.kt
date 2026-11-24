@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.mytv.cn.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.mytv.cn.data.AppSettings
import com.mytv.cn.data.AuthorInfo
import com.mytv.cn.data.ChannelItem
import com.mytv.cn.data.CommentItem
import com.mytv.cn.data.FeedKind
import com.mytv.cn.data.LiveRoomItem
import com.mytv.cn.data.ProfileTab
import com.mytv.cn.data.RootTab
import com.mytv.cn.data.VideoItem
import com.mytv.cn.data.formatCount
import com.mytv.cn.network.QrLoginStatus
import com.mytv.cn.player.PlayerState
import kotlinx.coroutines.flow.distinctUntilChanged

private val BrandRed = Color(0xFFFE2C55)
private val BrandCyan = Color(0xFF25F4EE)
private val AppBlack = Color(0xFF0E0E0F)
private val PanelBlack = Color(0xFF18181B)
private val TextMuted = Color(0xFFB8B8BD)

private val MyTvColors: ColorScheme = darkColorScheme(
    primary = BrandRed,
    onPrimary = Color.White,
    secondary = BrandCyan,
    background = AppBlack,
    onBackground = Color.White,
    surface = PanelBlack,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF29292D),
    onSurfaceVariant = TextMuted
)

@Composable
fun MyTvApp(viewModel: MainViewModel, isInPictureInPicture: Boolean) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.message) {
        if (ui.message.isNotBlank()) {
            snackbar.showSnackbar(ui.message)
            viewModel.dismissMessage()
        }
    }

    MaterialTheme(colorScheme = MyTvColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = AppBlack) {
            if (isInPictureInPicture) {
                PlayerSurface(viewModel.player, Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize()) {
                    when (ui.selectedTab) {
                        RootTab.HOME, RootTab.FEATURED -> VideoFeedScreen(
                            ui = ui,
                            player = viewModel.player,
                            playerState = playerState,
                            onSelectVideo = viewModel::selectVideo,
                            onNext = viewModel::nextVideo,
                            onPrevious = viewModel::previousVideo,
                            onTogglePlayback = viewModel::togglePlayback,
                            onFeedKind = viewModel::selectFeedKind,
                            onFeaturedChannel = viewModel::selectFeaturedChannel,
                            onLike = viewModel::toggleLike,
                            onCollect = viewModel::toggleCollected,
                            onComments = viewModel::showComments,
                            onRefresh = viewModel::refresh
                        )
                        RootTab.LIVE -> LiveScreen(
                            ui = ui,
                            player = viewModel.player,
                            playerState = playerState,
                            onMode = viewModel::loadLive,
                            onOpen = viewModel::openLive,
                            onClose = viewModel::closeLive,
                            onRefresh = viewModel::refresh
                        )
                        RootTab.SEARCH -> SearchScreen(
                            ui = ui,
                            history = settings.searchHistory,
                            onQuery = viewModel::updateSearchQuery,
                            onSearch = viewModel::submitSearch,
                            onVideo = viewModel::openVideo,
                            onRefresh = viewModel::refresh
                        )
                        RootTab.ME -> ProfileScreen(
                            ui = ui,
                            settings = settings,
                            onTab = viewModel::selectProfileTab,
                            onVideo = viewModel::openVideo,
                            onLogin = viewModel::startQrLogin,
                            onCompanion = viewModel::startCompanionServer,
                            onSettings = viewModel::showSettings,
                            onRefresh = viewModel::refresh
                        )
                    }

                    RootBottomBar(
                        selected = ui.selectedTab,
                        onSelected = viewModel::selectRootTab,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )

                    if (ui.loading) {
                        Box(
                            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = BrandRed)
                        }
                    }

                    SnackbarHost(
                        hostState = snackbar,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp)
                    )
                }

                if (ui.commentsOpen) {
                    CommentsSheet(
                        comments = ui.comments,
                        loading = ui.commentsLoading,
                        onDismiss = viewModel::hideComments
                    )
                }
                if (ui.settingsOpen) {
                    SettingsDialog(
                        settings = settings,
                        onUpdate = viewModel::updateSettings,
                        onCookie = viewModel::setCookie,
                        onDismiss = viewModel::hideSettings
                    )
                }
                if (ui.qrLogin.status != QrLoginStatus.IDLE) {
                    QrLoginDialog(ui, viewModel::closeQrLogin)
                }
                if (ui.companionQr != null) {
                    CompanionDialog(ui, viewModel::stopCompanionServer)
                }
            }
        }
    }
}

@Composable
private fun VideoFeedScreen(
    ui: MainUiState,
    player: Player,
    playerState: PlayerState,
    onSelectVideo: (Int) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayback: () -> Unit,
    onFeedKind: (FeedKind) -> Unit,
    onFeaturedChannel: (ChannelItem) -> Unit,
    onLike: () -> Unit,
    onCollect: () -> Unit,
    onComments: () -> Unit,
    onRefresh: () -> Unit
) {
    if (ui.videos.isEmpty()) {
        EmptyState(ui.message.ifBlank { "正在获取内容" }, onRefresh)
        FeedTopBar(ui, onFeedKind, onFeaturedChannel)
        return
    }

    val firstId = ui.videos.firstOrNull()?.id
    val pagerState = rememberPagerState(
        initialPage = ui.currentVideoIndex.coerceIn(0, ui.videos.lastIndex),
        pageCount = { ui.videos.size }
    )
    val context = LocalContext.current

    LaunchedEffect(firstId) {
        if (ui.videos.isNotEmpty()) pagerState.scrollToPage(ui.currentVideoIndex.coerceIn(0, ui.videos.lastIndex))
    }
    LaunchedEffect(pagerState, ui.videos.size) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page -> if (page != ui.currentVideoIndex) onSelectVideo(page) }
    }

    Box(Modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { ui.videos[it].id }
        ) { page ->
            val item = ui.videos[page]
            Box(
                Modifier.fillMaxSize().background(Color.Black)
                    .clickable(onClickLabel = if (playerState.isPlaying) "暂停" else "播放") { onTogglePlayback() }
            ) {
                if (page == pagerState.currentPage) {
                    PlayerSurface(player, Modifier.fillMaxSize())
                } else {
                    AsyncImage(
                        model = item.cover,
                        contentDescription = item.description,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                if (page == pagerState.currentPage && !playerState.isPlaying && !playerState.loading) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = "播放",
                        tint = Color.White.copy(alpha = .88f),
                        modifier = Modifier.align(Alignment.Center).size(74.dp)
                    )
                }

                VideoMeta(item, Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 86.dp, bottom = 88.dp))
                VideoActions(
                    item = item,
                    onLike = onLike,
                    onComments = onComments,
                    onCollect = onCollect,
                    onShare = { shareVideo(context, item) },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp, top = 84.dp)
                )
            }
        }

        FeedTopBar(ui, onFeedKind, onFeaturedChannel)

        Row(
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvEdgeButton("上一条", onPrevious)
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, top = 390.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvEdgeButton("下一条", onNext)
        }

        if (playerState.loading) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.Center).size(38.dp)
            )
        }
        if (playerState.error.isNotBlank()) {
            Text(
                playerState.error,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(.72f), RoundedCornerShape(8.dp)).padding(12.dp)
            )
        }
        if (playerState.durationMs > 0) {
            LinearProgressIndicator(
                progress = { (playerState.positionMs.toFloat() / playerState.durationMs).coerceIn(0f, 1f) },
                color = BrandRed,
                trackColor = Color.White.copy(alpha = .18f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 68.dp).fillMaxWidth().height(2.dp)
            )
        }
    }
}

@Composable
private fun FeedTopBar(
    ui: MainUiState,
    onFeedKind: (FeedKind) -> Unit,
    onFeaturedChannel: (ChannelItem) -> Unit
) {
    val home = listOf(
        FeedKind.RECOMMEND to "推荐",
        FeedKind.FOLLOWING to "关注",
        FeedKind.MOBILE to "手机版"
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (ui.selectedTab == RootTab.HOME) {
            items(home.size) { index ->
                val (kind, title) = home[index]
                ChannelPill(title, ui.feedKind == kind) { onFeedKind(kind) }
            }
        } else {
            items(ui.featuredChannels.size) { index ->
                val channel = ui.featuredChannels[index]
                ChannelPill(channel.title, ui.selectedChannelId == channel.id) { onFeaturedChannel(channel) }
            }
        }
    }
}

@Composable
private fun ChannelPill(title: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = title,
        color = Color.White,
        fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    focused -> Color.White.copy(alpha = .28f)
                    selected -> BrandRed
                    else -> Color.Black.copy(alpha = .46f)
                }
            )
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun VideoMeta(item: VideoItem, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Color.Black.copy(alpha = .38f), RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("@${item.author.nickname.ifBlank { "抖音用户" }}", color = Color.White, fontWeight = FontWeight.Bold)
        if (item.description.isNotBlank()) {
            Text(item.description, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        item.mix?.let {
            Text("合集 · ${it.name}  ${it.currentEpisode}/${it.totalEpisodes}", color = BrandCyan, fontSize = 13.sp)
        }
    }
}

@Composable
private fun VideoActions(
    item: VideoItem,
    onLike: () -> Unit,
    onComments: () -> Unit,
    onCollect: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AsyncImage(
            model = item.author.avatar,
            contentDescription = item.author.nickname,
            modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.DarkGray),
            contentScale = ContentScale.Crop
        )
        VideoAction(Icons.Rounded.Favorite, if (item.liked) BrandRed else Color.White, formatCount(item.stats.digg), "喜欢", onLike)
        VideoAction(Icons.Rounded.ChatBubble, Color.White, formatCount(item.stats.comment), "评论", onComments)
        VideoAction(Icons.Rounded.Bookmark, if (item.collected) BrandRed else Color.White, formatCount(item.stats.collect), "收藏", onCollect)
        VideoAction(Icons.Rounded.Share, Color.White, formatCount(item.stats.share), "分享", onShare)
    }
}

@Composable
private fun VideoAction(
    icon: ImageVector,
    tint: Color,
    count: String,
    description: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.semantics { contentDescription = description }.clickable(onClick = onClick).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
        Text(count, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun LiveScreen(
    ui: MainUiState,
    player: Player,
    playerState: PlayerState,
    onMode: (Boolean) -> Unit,
    onOpen: (LiveRoomItem) -> Unit,
    onClose: () -> Unit,
    onRefresh: () -> Unit
) {
    val room = ui.currentLive
    if (room != null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PlayerSurface(player, Modifier.fillMaxSize())
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(onClick = onClose) { Text("返回直播列表") }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(room.title, color = Color.White, fontWeight = FontWeight.Bold)
                    Text("${room.owner.nickname}  ${room.onlineText}", color = TextMuted, fontSize = 13.sp)
                }
            }
            if (playerState.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = BrandRed)
            if (!playerState.isPlaying && !playerState.loading) {
                IconButton(onClick = { if (playerState.isPlaying) player.pause() else player.play() }, Modifier.align(Alignment.Center).size(72.dp)) {
                    Icon(Icons.Rounded.PlayArrow, "播放直播", Modifier.size(56.dp), tint = Color.White)
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(AppBlack).statusBarsPadding().padding(bottom = 68.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChannelPill("推荐直播", !ui.liveFollowing) { onMode(false) }
            ChannelPill("关注直播", ui.liveFollowing) { onMode(true) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "刷新直播") }
        }
        if (ui.liveRooms.isEmpty()) {
            EmptyState(ui.message.ifBlank { "暂无直播" }, onRefresh)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(220.dp),
                contentPadding = PaddingValues(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(ui.liveRooms, key = { it.roomId }) { item ->
                    LiveCard(item) { onOpen(item) }
                }
            }
        }
    }
}

@Composable
private fun LiveCard(item: LiveRoomItem, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White.copy(.16f) else PanelBlack)
            .clickable(onClick = onClick)
            .focusable()
    ) {
        Box {
            AsyncImage(
                model = item.cover,
                contentDescription = item.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                contentScale = ContentScale.Crop
            )
            Text(
                "直播",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(8.dp).background(BrandRed, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Text(item.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp, 9.dp, 10.dp, 2.dp))
        Text(
            "${item.owner.nickname}  ${item.onlineText}",
            color = TextMuted,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(10.dp, 0.dp, 10.dp, 10.dp)
        )
    }
}

@Composable
private fun SearchScreen(
    ui: MainUiState,
    history: List<String>,
    onQuery: (String) -> Unit,
    onSearch: (String) -> Unit,
    onVideo: (VideoItem) -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(AppBlack).statusBarsPadding().imePadding().padding(bottom = 68.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = ui.searchQuery,
                onValueChange = onQuery,
                placeholder = { Text("搜索视频、用户或直播") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(ui.searchQuery) }),
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { onSearch(ui.searchQuery) }) { Text("搜索") }
        }

        if (ui.searchResults.videos.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(ui.searchResults.videos, key = { it.id }) { video ->
                    VideoGridCard(video) { onVideo(video) }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    SearchWords("热搜", ui.hotWords, onQuery, onSearch)
                }
                item {
                    SearchWords("搜索历史", history, onQuery, onSearch)
                }
                if (ui.message.isNotBlank()) {
                    item { EmptyState(ui.message, onRefresh) }
                }
            }
        }
    }
}

@Composable
private fun SearchWords(title: String, values: List<String>, onQuery: (String) -> Unit, onSearch: (String) -> Unit) {
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(values.size) { index ->
                val value = values[index]
                ChannelPill(value, false) {
                    onQuery(value)
                    onSearch(value)
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(
    ui: MainUiState,
    settings: AppSettings,
    onTab: (ProfileTab) -> Unit,
    onVideo: (VideoItem) -> Unit,
    onLogin: () -> Unit,
    onCompanion: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(AppBlack).statusBarsPadding().padding(bottom = 68.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "设置") }
        }
        val profile = ui.profile
        if (!settings.loggedIn || profile == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Person, null, Modifier.size(76.dp), tint = TextMuted)
                    Text("登录后查看个人主页", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("支持抖音扫码、粘贴 Cookie 或局域网推送", color = TextMuted)
                    Button(onClick = onLogin) { Text("扫码登录") }
                    OutlinedButton(onClick = onCompanion) { Text("局域网推送") }
                    OutlinedButton(onClick = onSettings) { Text("粘贴 Cookie") }
                }
            }
            return
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AsyncImage(
                model = profile.avatar,
                contentDescription = profile.nickname,
                modifier = Modifier.size(76.dp).clip(CircleShape).background(Color.DarkGray),
                contentScale = ContentScale.Crop
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(profile.nickname, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(profile.signature.ifBlank { "这个人很神秘" }, color = TextMuted, maxLines = 2)
                Text(
                    "${formatCount(profile.followingCount)} 关注   ${formatCount(profile.followerCount)} 粉丝   ${formatCount(profile.totalFavorited)} 获赞",
                    color = Color.White,
                    fontSize = 13.sp
                )
            }
            OutlinedButton(onClick = onRefresh) { Text("刷新") }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ProfileTab.entries.size) { index ->
                val tab = ProfileTab.entries[index]
                ChannelPill(tab.label, ui.profileTab == tab) { onTab(tab) }
            }
        }

        when {
            ui.profileVideos.isNotEmpty() -> LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ui.profileVideos, key = { it.id }) { video -> VideoGridCard(video) { onVideo(video) } }
            }
            ui.profileUsers.isNotEmpty() -> LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ui.profileUsers.size) { index -> AuthorRow(ui.profileUsers[index]) }
            }
            ui.profileCollections.isNotEmpty() -> LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ui.profileCollections.size) { index ->
                    val item = ui.profileCollections[index]
                    Text(
                        "${item.title} · ${item.count} 集",
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth().background(PanelBlack, RoundedCornerShape(10.dp)).padding(16.dp)
                    )
                }
            }
            else -> EmptyState("这里还没有内容", onRefresh)
        }
    }
}

@Composable
private fun AuthorRow(author: AuthorInfo) {
    Row(
        Modifier.fillMaxWidth().background(PanelBlack, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncImage(
            model = author.avatar,
            contentDescription = author.nickname,
            modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.DarkGray),
            contentScale = ContentScale.Crop
        )
        Column(Modifier.weight(1f)) {
            Text(author.nickname, color = Color.White, fontWeight = FontWeight.Bold)
            Text("${formatCount(author.followerCount)} 粉丝", color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun VideoGridCard(item: VideoItem, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White.copy(.16f) else PanelBlack)
            .clickable(onClick = onClick)
            .focusable()
    ) {
        AsyncImage(
            model = item.cover,
            contentDescription = item.description,
            modifier = Modifier.fillMaxWidth().aspectRatio(if (item.isVertical) .75f else 16f / 9f),
            contentScale = ContentScale.Crop
        )
        Text(
            item.description.ifBlank { item.author.nickname },
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            modifier = Modifier.padding(9.dp)
        )
    }
}

@Composable
private fun CommentsSheet(comments: List<CommentItem>, loading: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = PanelBlack,
        modifier = Modifier.fillMaxHeight(.78f)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("评论 ${comments.size}", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "关闭评论") }
        }
        if (loading) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandRed)
            }
        } else if (comments.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Text("暂无评论", color = TextMuted)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
                items(comments.size, key = { comments[it].id }) { index ->
                    CommentRow(comments[index])
                }
            }
        }
    }
}

@Composable
private fun CommentRow(item: CommentItem) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AsyncImage(
            model = item.user.avatar,
            contentDescription = item.user.nickname,
            modifier = Modifier.size(38.dp).clip(CircleShape).background(Color.DarkGray),
            contentScale = ContentScale.Crop
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.user.nickname.ifBlank { "抖音用户" }, color = TextMuted, fontSize = 12.sp)
            Text(item.text, color = Color.White)
            if (item.replyCount > 0) Text("${item.replyCount} 条回复", color = BrandCyan, fontSize = 12.sp)
        }
        Text("♥ ${formatCount(item.diggCount)}", color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun SettingsDialog(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onCookie: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var cookie by remember(settings.cookie) { mutableStateOf(settings.cookie) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(480.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item { SettingSwitch("画中画", settings.pipEnabled) { onUpdate { value -> value.copy(pipEnabled = it) } } }
                item { SettingSwitch("视频弹幕", settings.videoDanmaku) { onUpdate { value -> value.copy(videoDanmaku = it) } } }
                item { SettingSwitch("直播弹幕", settings.liveDanmaku) { onUpdate { value -> value.copy(liveDanmaku = it) } } }
                item { SettingSwitch("播放进度条", settings.playerProgressBar) { onUpdate { value -> value.copy(playerProgressBar = it) } } }
                item { SettingSwitch("精选自动播放", settings.featuredAutoPlay) { onUpdate { value -> value.copy(featuredAutoPlay = it) } } }
                item {
                    Text("登录 Cookie", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    OutlinedTextField(
                        value = cookie,
                        onValueChange = { cookie = it },
                        placeholder = { Text("粘贴完整 Cookie") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    FilledTonalButton(onClick = { onCookie(cookie) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("保存 Cookie")
                    }
                }
                item {
                    Text(
                        "账号：${settings.currentAccount?.name ?: "默认配置"}\n视频画质：${settings.videoResolution.label}\n直播画质：${settings.liveResolution.label}\n编码：${settings.codecPreference.label}",
                        color = TextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("完成") } }
    )
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun QrLoginDialog(ui: MainUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("抖音扫码登录") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ui.qrLogin.qrBitmap?.let { bitmap ->
                    Image(bitmap.asImageBitmap(), "抖音登录二维码", Modifier.size(260.dp).background(Color.White).padding(8.dp))
                }
                if (ui.qrLogin.status == QrLoginStatus.STARTING) CircularProgressIndicator(color = BrandRed)
                Text(ui.qrLogin.message.ifBlank { "正在准备二维码" }, color = TextMuted)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun CompanionDialog(ui: MainUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("局域网推送") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ui.companionQr?.let { bitmap ->
                    Image(bitmap.asImageBitmap(), "局域网推送地址二维码", Modifier.size(260.dp).background(Color.White).padding(8.dp))
                }
                Text(ui.companionUrl, color = BrandCyan)
                Text("手机与本机连接同一网络后扫码，可推送 Cookie 和互动凭据。", color = TextMuted)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("停止服务") } }
    )
}

@Composable
private fun RootBottomBar(selected: RootTab, onSelected: (RootTab) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf(
        Triple(RootTab.HOME, Icons.Rounded.Home, "首页"),
        Triple(RootTab.FEATURED, Icons.Rounded.Favorite, "精选"),
        Triple(RootTab.LIVE, Icons.Rounded.LiveTv, "直播"),
        Triple(RootTab.SEARCH, Icons.Rounded.Search, "搜索"),
        Triple(RootTab.ME, Icons.Rounded.Person, "我")
    )
    Row(
        modifier.fillMaxWidth().height(68.dp).background(Color.Black.copy(alpha = .88f)).navigationBarsPadding(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (tab, icon, label) ->
            var focused by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused }
                    .clickable { onSelected(tab) }.focusable().padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, label, tint = if (selected == tab || focused) Color.White else TextMuted, modifier = Modifier.size(24.dp))
                Text(
                    label,
                    color = if (selected == tab || focused) Color.White else TextMuted,
                    fontSize = 11.sp,
                    fontWeight = if (selected == tab) FontWeight.Bold else FontWeight.Normal
                )
                if (selected == tab) Box(Modifier.padding(top = 3.dp).width(18.dp).height(2.dp).background(BrandRed))
            }
        }
    }
}

@Composable
private fun EmptyState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, color = TextMuted)
            OutlinedButton(onClick = onRetry) {
                Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.width(6.dp))
                Text("重试")
            }
        }
    }
}

@Composable
private fun TvEdgeButton(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        color = Color.White,
        fontSize = 10.sp,
        modifier = Modifier.onFocusChanged { focused = it.isFocused }
            .background(if (focused) BrandRed else Color.Black.copy(alpha = .22f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

@Composable
private fun PlayerSurface(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
            }
        },
        update = { view -> if (view.player !== player) view.player = player },
        modifier = modifier.background(Color.Black)
    )
}

private fun shareVideo(context: Context, item: VideoItem) {
    val text = buildString {
        if (item.description.isNotBlank()) appendLine(item.description)
        append("https://www.douyin.com/video/${item.id}")
    }
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            },
            "分享视频"
        )
    )
}
