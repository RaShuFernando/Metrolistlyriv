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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.runtime.getValue

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

    private val currentLyrics = MutableStateFlow<ParsedOverlayLyrics?>(null)

    override fun onCreate() {
        super.onCreate()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

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
                val lyrics by currentLyrics.collectAsState()
                val positionMs by currentPositionMs.collectAsState()
                val fontSizeSp by OverlayLyricsPreferences.getOverlayFontSize(this@OverlayLyricsService)
                    .collectAsState(initial = 18f)
                val enabled by OverlayLyricsPreferences.getOverlayEnabled(this@OverlayLyricsService)
                    .collectAsState(initial = true)

                if (enabled && lyrics != null && lyrics!!.entries.isNotEmpty()) {
                    OverlayLyricsView(
                        overlayLyrics = lyrics!!,
                        positionMs = positionMs,
                        fontSizeSp = fontSizeSp
                    )
                }
            }
        }

        try {
            windowManager?.addView(composeView, layoutParams)
            lifecycleOwner.lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun observeTrackChanges() {
        serviceScope.launch {
            currentVideoId.collectLatest { videoId ->
                lyricsJob?.cancel()
                if (videoId.isBlank()) {
                    currentLyrics.value = null
                    return@collectLatest
                }

                lyricsJob = launch {
                    OverlayLyricsEngine.observeLyricsForTrack(this@OverlayLyricsService, videoId)
                        .collect { parsedLyrics ->
                            currentLyrics.value = parsedLyrics
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
    positionMs: Long,
    fontSizeSp: Float
) {
    val currentEntry = remember(overlayLyrics.entries, positionMs) {
        val entries = overlayLyrics.entries
        if (entries.isEmpty()) null
        else {
            var index = entries.binarySearch { (it.time - positionMs).toInt() }
            if (index < 0) {
                index = -index - 2
            }
            if (index >= 0 && index < entries.size) entries[index] else null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = currentEntry,
            transitionSpec = {
                (fadeIn() togetherWith fadeOut())
            },
            label = "LyricLineTransition"
        ) { entry ->
            if (entry != null && entry.text.isNotBlank()) {
                val words = entry.words
                val hasWordTimestamps = !words.isNullOrEmpty()

                if (hasWordTimestamps) {
                    // Word / Syllable synced animation
                    WordSyncedLyricLine(
                        entry = entry,
                        positionMs = positionMs,
                        fontSizeSp = fontSizeSp
                    )
                } else {
                    // Line synced smooth view
                    Text(
                        text = entry.text,
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
        }
    }
}

@Composable
fun WordSyncedLyricLine(
    entry: LyricsEntry,
    positionMs: Long,
    fontSizeSp: Float
) {
    val positionSeconds = positionMs / 1000.0
    val words = entry.words ?: emptyList()

    val annotatedString = buildAnnotatedString {
        words.forEach { word ->
            val isActive = positionSeconds >= word.startTime && positionSeconds <= word.endTime
            val isPassed = positionSeconds > word.endTime

            val color = when {
                isActive -> Color(0xFFFFD700) // Vibrant Gold highlight
                isPassed -> Color.White
                else -> Color.White.copy(alpha = 0.6f)
            }

            withStyle(
                style = SpanStyle(
                    color = color,
                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                    shadow = Shadow(
                        color = Color.Black,
                        offset = Offset(2f, 2f),
                        blurRadius = 8f
                    )
                )
            ) {
                append(word.text)
                if (word.hasTrailingSpace) {
                    append(" ")
                }
            }
        }
    }

    Text(
        text = annotatedString,
        style = TextStyle(
            fontSize = fontSizeSp.sp,
            textAlign = TextAlign.Center
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
