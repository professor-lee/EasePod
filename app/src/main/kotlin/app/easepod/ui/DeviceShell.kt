package app.easepod.ui

import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.easepod.core.*
import app.easepod.core.RepeatMode
import app.easepod.plugins.ThemePalette
import app.easepod.plugins.ThemeManager
import app.easepod.plugins.ThemeFont
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import kotlin.math.*

private val Ink = Color(0xff101820)
private val Muted = Color(0xff64717c)
private val LocalEasePodTheme = staticCompositionLocalOf { ThemeManager.BUNDLED_SILVER }

@Composable
fun DeviceShell(model: AppModel, modifier: Modifier = Modifier) {
    val theme = model.app.themes.theme(model.activeThemeId)
    val font = when (theme.typography.font) {
        ThemeFont.SYSTEM -> FontFamily.Default
        ThemeFont.SANS -> FontFamily.SansSerif
        ThemeFont.SERIF -> FontFamily.Serif
        ThemeFont.MONOSPACE -> FontFamily.Monospace
    }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color(theme.palette.highlight))) {
      CompositionLocalProvider(LocalEasePodTheme provides theme, LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = font)) {
        val frameBackground = if (theme.palette.usesFlatSurfaces) Modifier.background(Color(theme.palette.frameTop))
            else Modifier.background(Brush.verticalGradient(listOf(Color(theme.palette.frameTop), Color(theme.palette.frameBottom))))
        Box(modifier.fillMaxSize()
            .lockScreenSwipeToUnlock(model.settings.lockScreenOverlay && model.locked, model.gestureEpoch, model::unlockOverlay)
            .then(frameBackground)) {
            theme.assets.shellTexture?.let { texture ->
                AsyncImage(texture, null, Modifier.matchParentSize().graphicsLayer { alpha = .15f }, contentScale = ContentScale.Crop)
            }
            DeviceFrame(model)
        }
      }
    }
}

@Composable private fun DeviceFrame(model: AppModel) {
    val palette = model.app.themes.palette(model.activeThemeId)
    val page = model.page()
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(imeVisible) { model.imeVisibilityChanged(imeVisible) }
    LaunchedEffect(model.editing) { if (!model.editing) { keyboard?.hide(); focus.clearFocus() } }
    BackHandler(model.route.id != "home" || model.dialog != null || model.editing || model.hidingIme || model.valueDraft != null) { model.back() }
    val inputLayout = model.editing && imeVisible || model.hidingIme
    BoxWithConstraints(Modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top + WindowInsetsSides.Bottom))
        .imePadding().padding(horizontal = 20.dp, vertical = 20.dp)) {
        val lcdHeight = if (inputLayout) maxHeight - 12.dp else maxHeight * .48f
        val wheelSize = minOf(maxWidth - 20.dp, (maxHeight - lcdHeight) * .85f, 250.dp)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(lcdHeight.coerceAtLeast(180.dp)).clip(RoundedCornerShape(6.dp))
                .background(Color(0xff16181a)).padding(5.dp)) {
                Column(Modifier.fillMaxSize().background(Color.White)) {
                    StatusBar(page.title, model, palette)
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            if (page.kind in setOf("search", "form", "password")) InputField(model, page.kind == "password")
                            Box(Modifier.weight(1f).guardPointers(model.settings.touchGuard, model.gestureEpoch)) {
                                when {
                                    page.kind == "now" && model.playback.current != null -> NowPlaying(model)
                                    page.kind == "lyrics" -> Lyrics(model)
                                    page.kind == "auth" -> AuthPage(model, page, palette)
                                    page.kind == "coverflow" && model.coverItems.isNotEmpty() -> CoverFlow(model)
                                    page.split && !model.settings.largeText -> Row(Modifier.fillMaxSize()) {
                                        Box(Modifier.weight(.54f).fillMaxHeight()) { MenuList(page, model, palette) }
                                        Preview(page.rows.getOrNull(model.route.focus)?.title.orEmpty(), palette, Modifier.weight(.46f).fillMaxHeight())
                                    }
                                    else -> MenuList(page, model, palette)
                                }
                            }
                        }
                        model.dialog?.let { dialog ->
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)).guardPointers(model.settings.touchGuard, model.gestureEpoch).padding(12.dp), contentAlignment = Alignment.Center) {
                                DialogContents(dialog, palette, model.settings.largeText, model::dialogActivate)
                            }
                        }
                        model.valueDraft?.let { draft ->
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)).guardPointers(model.settings.touchGuard, model.gestureEpoch).padding(12.dp), contentAlignment = Alignment.Center) {
                                Column(Modifier.fillMaxWidth().background(Color(0xfff5f8fa), RoundedCornerShape(4.dp)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(draft.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text("${draft.value}${draft.unit}", color = Ink, fontSize = 24.sp, modifier = Modifier.padding(vertical = 10.dp))
                                    Slider(value = draft.value.toFloat(), onValueChange = { model.setDraftValue(it.roundToInt()) }, valueRange = draft.minimum.toFloat()..draft.maximum.toFloat())
                                    Row { TextButton(onClick = { model.back() }) { Text("取消") }; TextButton(onClick = { model.key(WheelKey.CENTER) }) { Text("确认") } }
                                }
                            }
                        }
                        if (model.notice.isNotBlank()) Text(model.notice, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).background(Color(0xee26313e), RoundedCornerShape(4.dp)).padding(8.dp))
                    }
                }
            }
            if (!inputLayout) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ClickWheel(model, palette, Modifier.size(wheelSize.coerceAtLeast(120.dp)))
            }
        }
    }
}

private fun Modifier.guardPointers(guarded: Boolean, epoch: Int): Modifier = pointerInput(guarded, epoch) {
    if (guarded) awaitPointerEventScope { while (true) { val event = awaitPointerEvent(PointerEventPass.Initial); event.changes.forEach { it.consume() } } }
}

@Composable private fun StatusBar(title: String, model: AppModel, palette: ThemePalette) {
    val background = if (palette.usesFlatSurfaces) Modifier.background(Color(palette.centerTop))
        else Modifier.background(Brush.verticalGradient(listOf(Color(0xfff9f9f9), Color(0xffbcbec1))))
    Row(Modifier.fillMaxWidth().height(29.dp).then(background).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (model.locked) Icon(Icons.Default.Lock, "设备已锁定", Modifier.size(13.dp), tint = Muted)
        if (model.settings.touchGuard) Icon(Icons.Default.TouchApp, "防误触已开启", Modifier.size(13.dp), tint = Muted)
        Icon(if (model.playback.playing) Icons.Default.PlayArrow else Icons.Default.Pause, if (model.playback.playing) "正在播放" else "已暂停", Modifier.size(15.dp), tint = if (palette.usesFlatSurfaces) Color(palette.key) else Color(0xff328ed0))
        val battery = model.deviceStatus.battery
        val batteryIcon = when {
            battery.charging -> Icons.Default.BatteryChargingFull
            battery.percent == null -> Icons.Default.BatteryUnknown
            battery.percent < 10 -> Icons.Default.Battery0Bar
            battery.percent < 25 -> Icons.Default.Battery1Bar
            battery.percent < 40 -> Icons.Default.Battery2Bar
            battery.percent < 55 -> Icons.Default.Battery3Bar
            battery.percent < 70 -> Icons.Default.Battery4Bar
            battery.percent < 85 -> Icons.Default.Battery5Bar
            battery.percent < 98 -> Icons.Default.Battery6Bar
            else -> Icons.Default.BatteryFull
        }
        Icon(batteryIcon, battery.percent?.let { "电量 $it%${if (battery.charging) "，正在充电" else ""}" } ?: "电量未知",
            Modifier.size(20.dp), tint = if (palette.usesFlatSurfaces) Color(palette.key)
                else if (!battery.charging && (battery.percent ?: 100) <= 15) Color(0xffae3030) else Color(0xff557045))
    }
}

@Composable private fun MenuList(page: LcdPage, model: AppModel, palette: ThemePalette) {
    val state = rememberLazyListState()
    val headingCount = (if (page.caption.isNotBlank()) 1 else 0) + (if (page.rows.isEmpty() || page.empty.isNotBlank() && page.rows.firstOrNull()?.id in setOf("本地音乐文件夹", "音乐", "插件管理", "添加音乐服务")) 1 else 0)
    LaunchedEffect(model.route.id, model.route.focus, page.rows.size) { if (page.rows.isNotEmpty()) state.animateScrollToItem((model.route.focus + headingCount).coerceAtMost(state.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)) }
    LazyColumn(Modifier.fillMaxSize(), state = state) {
        if (page.caption.isNotBlank()) item { Text(page.caption, Modifier.padding(8.dp), fontSize = 11.sp, lineHeight = 17.sp, color = Muted) }
        if (page.rows.isEmpty() || page.empty.isNotBlank() && page.rows.firstOrNull()?.id in setOf("本地音乐文件夹", "音乐", "插件管理", "添加音乐服务")) item {
            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.MusicNote, null, Modifier.size(34.dp), tint = Muted); Text(page.empty.ifBlank { "没有内容" }, fontSize = 15.sp, color = Ink, textAlign = TextAlign.Center) }
        }
        itemsIndexed(page.rows, key = { index, row -> "$index:${row.id}" }) { index, row ->
            val visibleRow = if (model.artworkAllowed(row.sourceId, row.accountScope)) row else row.copy(artwork = null)
            MenuRow(visibleRow, index == model.route.focus, palette, model.settings.largeText, { model.activate(index) }, { model.focus(index); row.hold?.invoke() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun MenuRow(row: LcdRow, selected: Boolean, palette: ThemePalette, large: Boolean, activate: () -> Unit, hold: () -> Unit) {
    val typography = LocalEasePodTheme.current.typography
    val background = if (selected) {
        if (palette.usesFlatSurfaces) Modifier.background(Color(palette.highlight))
        else Modifier.background(Brush.verticalGradient(listOf(Color(palette.highlight).copy(alpha = .7f), Color(palette.highlight))))
    } else Modifier
    Row(Modifier.fillMaxWidth().then(background).combinedClickable(onClick = activate, onLongClick = if (row.hold != null) hold else null)
        .semantics { this.selected = selected; if (row.toggle != null) stateDescription = if (row.toggle) "开启" else "关闭" }
        .heightIn(min = (if (large) typography.spacing.rootMenuRowDp else typography.spacing.menuRowDp).dp)
        .padding(horizontal = typography.spacing.horizontalPaddingDp.dp, vertical = typography.spacing.verticalPaddingDp.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        row.color?.let { Box(Modifier.size(17.dp).background(Color(it), CircleShape).border(1.dp, Color.Gray, CircleShape)) }
        if (row.artwork != null) Artwork(row.artwork, Modifier.size(30.dp))
        Text(row.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = (if (large) typography.fontSize.rootMenuSp else typography.fontSize.menuSp).sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else Ink)
        if (row.detail.isNotBlank()) Text(row.detail, Modifier.widthIn(max = 80.dp), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (selected) Color.White else Muted)
        if (row.toggle != null) Icon(if (row.toggle) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null, Modifier.size(15.dp), tint = if (selected) Color.White else Ink)
        else Box(Modifier.size(14.dp)) { if (selected) Icon(Icons.Default.ChevronRight, null, tint = Color.White) }
    }
}

@Composable private fun Preview(title: String, palette: ThemePalette, modifier: Modifier) {
    val background = if (palette.usesFlatSurfaces) Modifier.background(Color(palette.centerTop))
        else Modifier.background(Brush.verticalGradient(listOf(Color(0xffd3dfe6), Color(0xfff1f3f5))))
    val border = if (palette.usesFlatSurfaces) Color(palette.key) else Color(0xffafb7be)
    val iconTint = if (palette.usesFlatSurfaces) Color(palette.key) else Color(0xff879ba7)
    Box(modifier.then(background).border(.5.dp, border), contentAlignment = Alignment.Center) {
        Icon(when (title) { "设置", "主题", "轮盘", "插件", "插件市场", "本地与存储", "防误触" -> Icons.Default.Settings; "搜索" -> Icons.Default.Search; "歌单", "播放队列" -> Icons.Default.QueueMusic; else -> Icons.Default.MusicNote }, null, Modifier.size(84.dp).rotate(-12f), tint = iconTint)
        Text(title, Modifier.align(Alignment.BottomCenter).padding(12.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
    }
}

@Composable private fun InputField(model: AppModel, password: Boolean) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val permitTouch = !model.settings.touchGuard || model.inputException
    Row(Modifier.fillMaxWidth().background(Color(0xfff3f5f7)).guardPointers(!permitTouch, model.gestureEpoch).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(model.route.draft, onValueChange = { model.inputChanged(it.take(256)) },
            Modifier.weight(1f).background(Color.White, RoundedCornerShape(3.dp)).border(1.dp, Color(0xffaab7c3), RoundedCornerShape(3.dp)).padding(7.dp).focusRequester(requester).onFocusChanged { if (it.isFocused && !model.editing) model.beginInput() },
            singleLine = true, textStyle = LocalTextStyle.current.copy(color = Ink, fontSize = 14.sp),
            readOnly = !model.editing,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = if (model.route.id == "search") ImeAction.Search else ImeAction.Done),
            keyboardActions = KeyboardActions(onSearch = { model.submitInput() }, onDone = { model.submitInput() }))
        IconButton(onClick = { model.submitInput() }, Modifier.size(32.dp)) { Icon(if (model.route.id == "search") Icons.Default.Search else Icons.Default.Check, "完成", tint = Color(0xff267abe)) }
    }
    LaunchedEffect(model.editing, model.route.id) { if (model.editing) { requester.requestFocus(); keyboard?.show() } }
}

@Composable private fun NowPlaying(model: AppModel) {
    val track = model.playback.current ?: return
    if (model.nowMode == "lyrics") { Lyrics(model); return }
    val seekInteraction = remember { MutableInteractionSource() }
    LaunchedEffect(seekInteraction) { seekInteraction.interactions.collect { if (it is DragInteraction.Cancel) model.finishSeek(true) } }
    DisposableEffect(track.id, model.gestureEpoch) { onDispose { model.finishSeek(true) } }
    val artwork = track.artworkUri.takeIf { model.artworkAllowed(track.sourceId, track.accountScope) }
    val flatTheme = LocalEasePodTheme.current.palette.usesFlatSurfaces
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(.45f)) {
                val artworkModifier = if (flatTheme) Modifier.fillMaxWidth().aspectRatio(1f)
                    else Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { rotationY = 12f; cameraDistance = 20f * density }
                Artwork(artwork, artworkModifier)
                if (!flatTheme) ArtworkReflection(artwork, Modifier.fillMaxWidth().height(36.dp))
            }
            Column(Modifier.weight(.55f)) {
                Text(track.title, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(track.artist, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
                Text(track.albumTitle.orEmpty(), fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                Text("${model.playback.queue.indexOfFirst { it.id == model.playback.currentEntryId } + 1} / ${model.playback.queue.size}", fontSize = 12.sp, color = Ink, modifier = Modifier.padding(top = 10.dp))
                Text(model.playback.actualQuality, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Column(Modifier.padding(horizontal = 12.dp)) {
            if (model.nowMode == "volume") Slider(model.playback.volume, { model.app.player.volume(it) })
            else Slider(model.currentPosition.toFloat().coerceIn(0f, model.playback.durationMs.coerceAtLeast(1).toFloat()), { model.previewSeek(it.toLong()) },
                onValueChangeFinished = { model.finishSeek(false) }, enabled = model.playback.canSeek,
                interactionSource = seekInteraction, valueRange = 0f..model.playback.durationMs.coerceAtLeast(1).toFloat())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(time(model.currentPosition), fontSize = 11.sp, color = Ink); Text("-${time((model.playback.durationMs - model.currentPosition).coerceAtLeast(0))}", fontSize = 11.sp, color = Ink) }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(when { model.playback.buffering -> "缓冲中"; model.nowMode == "seek" -> "进度"; model.nowMode == "volume" -> "音量 ${(model.playback.volume * 100).toInt()}%"; else -> "" }, fontSize = 11.sp, color = Muted)
                if (model.playback.shuffle) Icon(Icons.Default.Shuffle, "随机播放", Modifier.size(14.dp), tint = Muted)
                if (model.playback.repeat != RepeatMode.OFF) Icon(Icons.Default.Repeat, "重复", Modifier.size(14.dp), tint = Muted)
            }
            model.playback.error?.let { Text(it, fontSize = 12.sp, color = Color(0xff9f3c32)); TextButton(onClick = { model.app.player.toggle() }) { Text("重试") } }
        }
    }
}

@Composable private fun Artwork(uri: String?, modifier: Modifier) {
    val flatTheme = LocalEasePodTheme.current.palette.usesFlatSurfaces
    val artworkFilter = remember(flatTheme) {
        if (!flatTheme) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }
    var failed by remember(uri) { mutableStateOf(false) }
    val placeholder = LocalEasePodTheme.current.assets.albumPlaceholder
    var fallbackFailed by remember(placeholder) { mutableStateOf(false) }
    val actual = if (uri != null && !failed) uri else placeholder?.takeUnless { fallbackFailed }
    var loaded by remember(actual) { mutableStateOf(false) }
    Box(modifier.background(Color(0xffeeeeee)).semantics {
        stateDescription = when {
            uri == null -> "无封面"
            loaded && !failed -> "封面已加载"
            failed -> "封面加载失败"
            else -> "封面加载中"
        }
    }, contentAlignment = Alignment.Center) {
        if (!loaded) Icon(Icons.Default.MusicNote, null, Modifier.fillMaxSize(.65f), tint = Color(0xffb5b5b5))
        if (actual != null) AsyncImage(actual, "专辑封面", Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            colorFilter = artworkFilter, onSuccess = { loaded = true }, onError = { if (actual == uri) failed = true else fallbackFailed = true })
    }
}

@Composable private fun ArtworkReflection(uri: String?, modifier: Modifier) {
    Box(modifier.clipToBounds().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .24f), Color.Transparent)), blendMode = BlendMode.DstIn)
        }) {
        Artwork(uri, Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { scaleY = -1f }.clearAndSetSemantics { })
    }
}

@Composable private fun CoverFlow(model: AppModel) {
    val albums = model.coverItems
    val current = model.coverPosition
    val flatTheme = LocalEasePodTheme.current.palette.usesFlatSurfaces
    val animation = remember { Animatable(current.toFloat()) }
    val reduced = model.settings.reducedMotion || android.provider.Settings.Global.getFloat(LocalContext.current.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    LaunchedEffect(current, reduced) { if (reduced) animation.snapTo(current.toFloat()) else animation.animateTo(current.toFloat(), tween(220, easing = FastOutSlowInEasing)) }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(0.dp))) {
            val center = maxWidth / 2
            val artworkSize = minOf(136.dp, maxHeight - 16.dp).coerceAtLeast(48.dp)
            val slots = if (albums.size == 1) listOf(current) else (floor(animation.value).toInt() - 2..ceil(animation.value).toInt() + 2).take(24)
            slots.sortedByDescending { abs(it - animation.value) }.forEach { slot ->
                val offset = slot - animation.value
                val album = albums[Math.floorMod(slot, albums.size)]
                val sourceId = model.pluginList.find { it.id == model.route.source }?.sourceId.orEmpty()
                val artwork = album.artworkUri.takeIf { model.route.source.isBlank() || model.artworkAllowed(sourceId, model.route.accountScope) }
                val scale = 1f - min(abs(offset), 1f) * .28f
                val x = offset.coerceIn(-2.5f, 2.5f) * 92
                Column(Modifier.offset(x = center - artworkSize / 2 + x.dp, y = (maxHeight - artworkSize * 1.3f) / 2).width(artworkSize).graphicsLayer {
                    scaleX = scale; scaleY = scale
                    if (!flatTheme) {
                        rotationY = -offset.coerceIn(-1f, 1f) * 40f
                        cameraDistance = 20 * density
                        alpha = if (abs(offset) > 1.5f) .4f else 1f
                    }
                }.clickable { model.selectCover(slot - current) }) {
                    Artwork(artwork, Modifier.size(artworkSize))
                    if (!flatTheme) {
                        Spacer(Modifier.height(2.dp))
                        ArtworkReflection(artwork, Modifier.fillMaxWidth().height(artworkSize * .3f))
                    }
                }
            }
        }
        Text(model.selectedAlbum()?.title.orEmpty(), Modifier.fillMaxWidth().padding(6.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(model.selectedAlbum()?.artist.orEmpty(), Modifier.fillMaxWidth().padding(bottom = 6.dp), fontSize = 11.sp, color = Muted, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        model.page().rows.forEachIndexed { index, row ->
            MenuRow(row, index == model.route.focus, model.app.themes.palette(model.activeThemeId), model.settings.largeText, { model.activate(index) }, {})
        }
    }
}

@Composable private fun Lyrics(model: AppModel) {
    val state = rememberLazyListState()
    LaunchedEffect(model.lyricFocus, model.lyricLines.size) { if (model.lyricLines.isNotEmpty()) state.animateScrollToItem(model.lyricFocus) }
    Column(Modifier.fillMaxSize()) {
        if (model.lyricLines.isEmpty()) {
            Text(model.lyricText, Modifier.weight(1f).fillMaxWidth().padding(14.dp), color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            if (model.lyricText.contains("失败")) {
                val retry = model.page().rows.firstOrNull()
                if (model.route.id == "lyrics" && retry != null) MenuRow(retry, true, model.app.themes.palette(model.activeThemeId), model.settings.largeText, { model.key(WheelKey.CENTER) }, {})
                else TextButton(onClick = model::retryLyrics) { Text("重试") }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state) {
                itemsIndexed(model.lyricLines) { index, line ->
                    Column(Modifier.fillMaxWidth().clickable { model.seekLyric(index) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(line.text.ifBlank { " " }, Modifier.fillMaxWidth(),
                            color = if (index == model.lyricFocus) Ink else Muted, fontSize = 14.sp, fontWeight = if (index == model.lyricFocus) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
                        line.translation?.takeIf { it.isNotBlank() && it != line.text }?.let { translation ->
                            Text(translation, Modifier.fillMaxWidth().padding(top = 3.dp), color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            TextButton(onClick = model::followLyrics, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("回到当前行", fontSize = 11.sp) }
        }
    }
}

@Composable private fun AuthPage(model: AppModel, page: LcdPage, palette: ThemePalette) {
    val payload = model.authSession?.qrContent
    val bitmap = remember(payload) {
        payload?.let { runCatching {
            val matrix = QRCodeWriter().encode(it, BarcodeFormat.QR_CODE, 320, 320)
            Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply {
                val pixels = IntArray(320 * 320) { index -> if (matrix[index % 320, index / 320]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                setPixels(pixels, 0, 320, 0, 0, 320, 320)
            }.asImageBitmap()
        }.getOrNull() }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val qrSize = minOf(maxWidth, maxHeight * .55f, 144.dp)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            bitmap?.let { Image(it, "账号授权二维码", Modifier.size(qrSize), contentScale = ContentScale.Fit) }
            Box(Modifier.weight(1f).fillMaxWidth()) { MenuList(page, model, palette) }
        }
    }
}

@Composable private fun DialogContents(dialog: LcdDialog, palette: ThemePalette, large: Boolean, activate: (Int) -> Unit) {
    val state = rememberLazyListState()
    if (dialog.document) {
        val lines = remember(dialog.message) { dialog.message.lines() }
        LaunchedEffect(dialog.scroll) { state.animateScrollToItem(dialog.scroll.coerceIn(0, lines.lastIndex.coerceAtLeast(0))) }
        Column(Modifier.fillMaxSize().background(Color(0xfff5f8fa), RoundedCornerShape(4.dp)).padding(8.dp)) {
            Text(dialog.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state) { itemsIndexed(lines) { _, line -> Text(line.ifBlank { " " }, fontSize = 12.sp, lineHeight = 18.sp, color = Ink) } }
            MenuRow(dialog.rows.first(), true, palette, large, { activate(0) }, {})
        }
        return
    }
    LaunchedEffect(dialog.focus) { state.animateScrollToItem(dialog.focus + 1) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp).background(Color(0xfff5f8fa), RoundedCornerShape(4.dp)).border(1.dp, Color(0xff536779), RoundedCornerShape(4.dp)), state = state) {
        item { Column(Modifier.padding(12.dp)) { Text(dialog.title, fontSize = 16.sp, color = Ink, fontWeight = FontWeight.Bold); if (dialog.message.isNotBlank()) Text(dialog.message, fontSize = 12.sp, lineHeight = 18.sp, color = Muted, modifier = Modifier.padding(top = 6.dp)) } }
        itemsIndexed(dialog.rows) { index, row -> MenuRow(row, index == dialog.focus, palette, large, { activate(index) }, {}) }
    }
}

@Composable private fun ClickWheel(model: AppModel, palette: ThemePalette, modifier: Modifier) {
    val view = LocalView.current
    val settings by rememberUpdatedState(model.settings)
    fun feedback() { if (settings.haptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); if (settings.clickSound) view.playSoundEffect(SoundEffectConstants.CLICK) }
    val gestureModifier = Modifier.pointerInput(model.gestureEpoch) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val stepAngle = WheelGeometry.stepAngle(settings.wheelSensitivity)
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.width / 2f
                val start = down.position
                val relative = start - center
                val key = WheelGeometry.key(relative.x, relative.y, radius)
                var lastAngle: Float? = WheelGeometry.angle(relative.x, relative.y)
                var accumulated = 0f
                var rotating = false
                var held = false
                var cancelled = false
                val timer = launch { delay(viewConfiguration.longPressTimeoutMillis); if (!rotating && !cancelled && key != null) { held = true; model.hold(key); feedback() } }
                down.consume()
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) { cancelled = true; break }
                        val change = event.changes.find { it.id == down.id }
                        if (change == null) { cancelled = true; break }
                        val displacement = (change.position - start).getDistance()
                        if (!change.pressed) {
                            if (!cancelled && !rotating && !held && displacement <= viewConfiguration.touchSlop && key != null) { model.key(key); feedback() }
                            change.consume(); break
                        }
                        if (change.isConsumed) { cancelled = true; break }
                        val point = change.position - center
                        val distance = point.getDistance()
                        if (displacement > viewConfiguration.touchSlop && !held) {
                            timer.cancel()
                            if (key == WheelKey.CENTER) { cancelled = true; break }
                            if (distance < radius * .4f || distance > radius + viewConfiguration.touchSlop) lastAngle = null
                            else {
                                val angle = WheelGeometry.angle(point.x, point.y)
                                lastAngle?.let { accumulated += WheelGeometry.delta(it, angle) }
                                lastAngle = angle
                                if (abs(accumulated) >= 8f) rotating = true
                                if (rotating) {
                                    val steps = (accumulated / stepAngle).toInt().coerceIn(-8, 8)
                                    if (steps != 0) { model.rotate(steps, fromPointer = true); feedback(); accumulated -= steps * stepAngle }
                                }
                            }
                        }
                        change.consume()
                    }
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                } finally {
                    timer.cancel()
                    model.endHold(cancelled)
                    model.endRotation(cancelled)
                }
            }
        }
    }
    Box(modifier.clip(CircleShape).background(Color(palette.wheel)).border(.5.dp, Color(0xffa4a6a7), CircleShape).then(gestureModifier)) {
        @Composable fun keyButton(key: WheelKey, label: String, modifier: Modifier, content: @Composable () -> Unit) {
            Box(modifier.semantics { contentDescription = label; onClick { model.key(key); true }; onLongClick { model.hold(key); model.endHold(false); true } }, contentAlignment = Alignment.Center) { content() }
        }
        keyButton(WheelKey.MENU, "菜单，返回", Modifier.align(Alignment.TopCenter).fillMaxWidth(.38f).fillMaxHeight(.26f)) { Text("MENU", color = Color(palette.key), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        keyButton(WheelKey.PREVIOUS, "上一首", Modifier.align(Alignment.CenterStart).fillMaxWidth(.28f).fillMaxHeight(.3f)) { Icon(Icons.Default.SkipPrevious, null, Modifier.size(25.dp), tint = Color(palette.key)) }
        keyButton(WheelKey.NEXT, "下一首", Modifier.align(Alignment.CenterEnd).fillMaxWidth(.28f).fillMaxHeight(.3f)) { Icon(Icons.Default.SkipNext, null, Modifier.size(25.dp), tint = Color(palette.key)) }
        keyButton(WheelKey.PLAY, "播放或暂停", Modifier.align(Alignment.BottomCenter).fillMaxWidth(.38f).fillMaxHeight(.26f)) { Row { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp), tint = Color(palette.key)); Icon(Icons.Default.Pause, null, Modifier.size(18.dp), tint = Color(palette.key)) } }
        val centerBackground = if (palette.usesFlatSurfaces) Modifier.background(Color(palette.centerTop))
            else Modifier.background(Brush.verticalGradient(listOf(Color(palette.centerTop), Color(palette.centerBottom))))
        keyButton(WheelKey.CENTER, "中心，确认", Modifier.align(Alignment.Center).fillMaxSize(.36f).clip(CircleShape).then(centerBackground).border(1.dp, Color(0xff989fa5), CircleShape)) { }
    }
}

private fun time(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
