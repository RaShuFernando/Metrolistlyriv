package com.metrolist.music.lyrics.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.metrolist.music.lyrics.LyricsEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import androidx.compose.runtime.getValue
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat

class ServiceLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    init {
        savedStateRegistryController.performRestore(null)
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store
}

sealed class LyricsUiState {
    object Hidden : LyricsUiState()
    object Loading : LyricsUiState()
    data class Success(val lyrics: ParsedOverlayLyrics) : LyricsUiState()
    object NotFound : LyricsUiState()
}

class OverlayLyricsService : Service() {

    companion object {
        private val _currentVideoId = MutableStateFlow("")
        val currentVideoId: StateFlow<String> = _currentVideoId.asStateFlow()

        private val _currentPositionMs = MutableStateFlow(0L)
        val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

        private val _isPlaying = MutableStateFlow(false)
        val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

        fun updatePlaybackState(videoId: String, positionMs: Long, playing: Boolean) {
            _currentVideoId.value = videoId
            _currentPositionMs.value = positionMs
            _isPlaying.value = playing
        }

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
            val intent = Intent(context, OverlayLyricsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayLyricsService::class.java)
            context.stopService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private val lifecycleOwner = ServiceLifecycleOwner()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var lyricsJob: Job? = null

    private val currentUiState = MutableStateFlow<LyricsUiState>(LyricsUiState.Hidden)

    override fun onCreate() {
        super.onCreate()
        
        // Start Foreground early to prevent ForegroundServiceDidNotStartInTimeException
        val channelId = "overlay_lyrics_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Overlay Lyrics Active",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Overlay Lyrics Active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
            
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                12345, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(12345, notification)
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        setupOverlayView()
        observeTrackChanges()
    }

    private fun setupOverlayView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120 // Distance from top edge
        }

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)

            setContent {
                val uiState by currentUiState.collectAsState()
                val isPlaying by isPlaying.collectAsState()
                
                val positionMs by currentPositionMs.collectAsState()
                val currentPositionProvider = { positionMs }
                val fontSizeSp by OverlayLyricsPreferences.getOverlayFontSize(this@OverlayLyricsService)
                    .collectAsState(initial = 18f)
                val enabled by OverlayLyricsPreferences.getOverlayEnabled(this@OverlayLyricsService)
                    .collectAsState(initial = true)
                val lineAnimation by OverlayLyricsPreferences.getLineAnimation(this@OverlayLyricsService)
                    .collectAsState(initial = 2)
                val wordAnimation by OverlayLyricsPreferences.getWordAnimation(this@OverlayLyricsService)
                    .collectAsState(initial = 2)
                val activeWordColor by OverlayLyricsPreferences.getActiveWordColor(this@OverlayLyricsService)
                    .collectAsState(initial = android.graphics.Color.parseColor("#FFD700"))
                val activeLineColor by OverlayLyricsPreferences.getActiveLineColor(this@OverlayLyricsService)
                    .collectAsState(initial = android.graphics.Color.WHITE)
                val inactiveLineColor by OverlayLyricsPreferences.getInactiveLineColor(this@OverlayLyricsService)
                    .collectAsState(initial = android.graphics.Color.parseColor("#A6FFFFFF"))

                if (enabled) {
                    AnimatedVisibility(
                        visible = isPlaying,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        when (uiState) {
                        is LyricsUiState.Success -> {
                            val lyrics = (uiState as LyricsUiState.Success).lyrics
                            if (lyrics.lines.isNotEmpty()) {
                                OverlayLyricsView(
                                    overlayLyrics = lyrics,
                                    currentPositionProvider = currentPositionProvider,
                                    fontSizeSp = fontSizeSp,
                                    lineAnimation = lineAnimation,
                                    wordAnimation = wordAnimation,
                                    activeWordColor = activeWordColor,
                                    activeLineColor = activeLineColor,
                                    inactiveLineColor = inactiveLineColor
                                )
                            }
                        }
                        is LyricsUiState.Loading -> {
                            var isVisible by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                kotlinx.coroutines.delay(1000)
                                isVisible = false
                            }
                            androidx.compose.animation.AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                StatusOverlayView(
                                    text = "Searching for lyrics...",
                                    fontSizeSp = fontSizeSp
                                )
                            }
                        }
                        is LyricsUiState.NotFound -> {
                            var isVisible by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                kotlinx.coroutines.delay(2000)
                                isVisible = false
                            }
                            androidx.compose.animation.AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                StatusOverlayView(
                                    text = "No lyrics found",
                                    fontSizeSp = fontSizeSp
                                )
                            }
                        }
                        is LyricsUiState.Hidden -> {}
                    }
                }
            }
        }
        }

        try {
            windowManager?.addView(composeView, layoutParams)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun observeTrackChanges() {
        serviceScope.launch {
            currentVideoId.flatMapLatest { videoId ->
                if (videoId.isBlank()) {
                    currentUiState.value = LyricsUiState.Hidden
                    kotlinx.coroutines.flow.flowOf(null)
                } else {
                    currentUiState.value = LyricsUiState.Loading
                    OverlayLyricsEngine.observeLyricsForTrack(this@OverlayLyricsService, videoId)
                }
            }.collect { parsedLyrics ->
                if (currentUiState.value != LyricsUiState.Hidden) {
                    if (parsedLyrics != null) {
                        currentUiState.value = LyricsUiState.Success(parsedLyrics)
                    } else {
                        currentUiState.value = LyricsUiState.NotFound
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        if (composeView != null && windowManager != null) {
            try {
                composeView?.disposeComposition()
                windowManager?.removeView(composeView)
            } catch (_: Exception) {}
        }
        
        lifecycleOwner.lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
}

@Composable
fun OverlayLyricsView(
    overlayLyrics: ParsedOverlayLyrics,
    currentPositionProvider: () -> Long,
    fontSizeSp: Float,
    lineAnimation: Int,
    wordAnimation: Int,
    activeWordColor: Int,
    activeLineColor: Int,
    inactiveLineColor: Int
) {
    val backgroundColor by OverlayLyricsPreferences.getBackgroundColor(LocalContext.current)
        .collectAsState(initial = android.graphics.Color.TRANSPARENT)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(backgroundColor))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        val enterTransition = when (lineAnimation) {
            1 -> fadeIn()
            2 -> slideInVertically { it } + fadeIn()
            3 -> androidx.compose.animation.scaleIn() + fadeIn()
            4 -> fadeIn() // 3D Flip fallback enter
            5 -> fadeIn() // Blur Reveal fallback enter
            else -> androidx.compose.animation.EnterTransition.None
        }
        val exitTransition = when (lineAnimation) {
            1 -> fadeOut() + androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top)
            2 -> slideOutVertically { -it } + fadeOut() + androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top)
            3 -> androidx.compose.animation.scaleOut() + fadeOut() + androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top)
            4 -> fadeOut() + androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top)
            5 -> fadeOut() + androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top)
            else -> androidx.compose.animation.ExitTransition.None
        }

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            val positionMs = currentPositionProvider()
            val windowLines = overlayLyrics.lines.filter { 
                it.startTimeMs <= positionMs + 4000 && it.endTimeMs >= positionMs - 3000 
            }
            
            windowLines.forEach { line ->
                val isLineVisible by androidx.compose.runtime.remember(line.startTimeMs, line.endTimeMs) { 
                    androidx.compose.runtime.derivedStateOf { 
                        val pos = currentPositionProvider()
                        pos in line.startTimeMs..line.endTimeMs
                    } 
                }
                
                androidx.compose.runtime.key(line.startTimeMs) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isLineVisible,
                        enter = enterTransition,
                        exit = exitTransition
                    ) {
                        val rotationX by transition.animateFloat(
                            transitionSpec = { androidx.compose.animation.core.tween() },
                            label = "rotationX"
                        ) { enterExitState ->
                            when (enterExitState) {
                                androidx.compose.animation.EnterExitState.PreEnter -> -90f
                                androidx.compose.animation.EnterExitState.Visible -> 0f
                                androidx.compose.animation.EnterExitState.PostExit -> 90f
                            }
                        }
                        val blurRadius by transition.animateDp(
                            transitionSpec = { androidx.compose.animation.core.tween() },
                            label = "blur"
                        ) { enterExitState ->
                            when (enterExitState) {
                                androidx.compose.animation.EnterExitState.PreEnter -> 8.dp
                                androidx.compose.animation.EnterExitState.Visible -> 0.dp
                                androidx.compose.animation.EnterExitState.PostExit -> 8.dp
                            }
                        }
                        val customModifier = when (lineAnimation) {
                            4 -> Modifier.graphicsLayer { this.rotationX = rotationX }
                            5 -> Modifier.blur(blurRadius)
                            else -> Modifier
                        }
                        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).then(customModifier)) {
                            if (line.text.isNotBlank()) {
                                val hasWordTimestamps = line.words.isNotEmpty()
                                if (hasWordTimestamps) {
                                    WordSyncedLyricLine(
                                        line = line,
                                        currentPositionProvider = currentPositionProvider,
                                        fontSizeSp = fontSizeSp,
                                        wordAnimation = wordAnimation,
                                        activeWordColor = activeWordColor,
                                        activeLineColor = activeLineColor,
                                        inactiveLineColor = inactiveLineColor
                                    )
                                } else {
                                    val isBg = line.isBackground
                                    val textAlign = when {
                                        isBg -> TextAlign.Right
                                        line.agent == "v2" -> TextAlign.Right
                                        else -> TextAlign.Start
                                    }
                                    val effectiveFontSize = if (isBg) fontSizeSp * 0.85f else fontSizeSp
                                    val baseColor = Color(activeLineColor)
                                    val finalColor = if (isBg) baseColor.copy(alpha = 0.65f) else baseColor

                                    Text(
                                        text = line.text,
                                        style = TextStyle(
                                            fontSize = effectiveFontSize.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontStyle = if (isBg) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                                            color = finalColor,
                                            textAlign = textAlign,
                                            shadow = Shadow(
                                                color = Color.Black,
                                                offset = Offset(2f, 2f),
                                                blurRadius = 8f
                                            )
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WordSyncedLyricLine(
    line: OverlayLyricLine,
    currentPositionProvider: () -> Long,
    fontSizeSp: Float,
    wordAnimation: Int,
    activeWordColor: Int,
    activeLineColor: Int,
    inactiveLineColor: Int
) {
    val words = line.words

    // Agent alignment logic for the whole line
    val isBg = line.isBackground
    
    val groupedWords = words.groupBy { word ->
        when {
            word.isBackground || isBg -> "bg"
            word.agent == "v2" || line.agent == "v2" -> "v2"
            else -> "v1"
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        groupedWords.forEach { (agentKey, agentWords) ->
            val textAlign = when (agentKey) {
                "bg", "v2" -> TextAlign.Right
                else -> TextAlign.Start
            }
            val effectiveFontSize = if (agentKey == "bg") fontSizeSp * 0.85f else fontSizeSp

            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(if (textAlign == TextAlign.Right) Alignment.End else Alignment.Start),
                horizontalArrangement = if (textAlign == TextAlign.Right) Arrangement.End else Arrangement.Start
            ) {
                agentWords.forEach { word ->
                    val isActive by androidx.compose.runtime.remember(word.startTimeMs, word.endTimeMs) { androidx.compose.runtime.derivedStateOf { currentPositionProvider() in word.startTimeMs..word.endTimeMs } }
                    val isPassed by androidx.compose.runtime.remember(word.endTimeMs) { androidx.compose.runtime.derivedStateOf { currentPositionProvider() > word.endTimeMs } }

                    val scale by animateFloatAsState(
                        targetValue = if (isActive && (wordAnimation == 2 || wordAnimation == 5)) 1.15f else 1.0f,
                        animationSpec = if (wordAnimation == 5) androidx.compose.animation.core.spring(dampingRatio = 0.3f, stiffness = 800f)
                                        else if (wordAnimation == 2) androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 500f)
                                        else androidx.compose.animation.core.tween(),
                        label = "ScaleAnim"
                    )
                    
                    val shadowRadius by animateFloatAsState(
                        targetValue = if (isActive && wordAnimation == 3) 16f else 8f,
                        label = "GlowAnim"
                    )

                    val translateY by animateFloatAsState(
                        targetValue = if (isActive && wordAnimation == 4) -10f else 0f,
                        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 500f),
                        label = "TranslateYAnim"
                    )

                    val karaokeProgress by androidx.compose.runtime.remember(word.startTimeMs, word.endTimeMs, wordAnimation) {
                        androidx.compose.runtime.derivedStateOf {
                            if (wordAnimation == 6) {
                                val pos = currentPositionProvider()
                                val durationMs = word.endTimeMs - word.startTimeMs
                                if (durationMs > 0) {
                                    ((pos - word.startTimeMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    if (pos > word.endTimeMs) 1f else 0f
                                }
                            } else {
                                0f
                            }
                        }
                    }
                    
                    val color by animateColorAsState(
                        targetValue = when {
                            isActive && wordAnimation != 6 -> Color(activeWordColor)
                            isPassed && wordAnimation != 6 -> Color(activeLineColor)
                            else -> Color(inactiveLineColor)
                        },
                        label = "ColorAnim"
                    )

                    val brush = if (wordAnimation == 6) {
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = listOf(Color(activeWordColor), Color(inactiveLineColor)),
                            stops = floatArrayOf(karaokeProgress, karaokeProgress)
                        )
                    } else null
                    
                    val style = if (brush != null) {
                        TextStyle(
                            brush = brush,
                            fontSize = effectiveFontSize.sp,
                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                            fontStyle = if (agentKey == "bg") androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                            shadow = Shadow(
                                color = if (isActive && wordAnimation == 3) Color(activeWordColor) else Color.Black,
                                offset = Offset(2f, 2f),
                                blurRadius = shadowRadius
                            )
                        )
                    } else {
                        TextStyle(
                            fontSize = effectiveFontSize.sp,
                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                            fontStyle = if (agentKey == "bg") androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                            shadow = Shadow(
                                color = if (isActive && wordAnimation == 3) Color(activeWordColor) else Color.Black,
                                offset = Offset(2f, 2f),
                                blurRadius = shadowRadius
                            )
                        )
                    }

                    Text(
                        text = word.text + if (word.hasTrailingSpace) " " else "",
                        color = if (wordAnimation == 6) Color.Unspecified else color,
                        style = style,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.translationY = translateY
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun StatusOverlayView(text: String, fontSizeSp: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                shadow = Shadow(
                    color = Color.Black,
                    offset = Offset(2f, 2f),
                    blurRadius = 8f
                )
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
