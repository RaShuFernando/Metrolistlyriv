package com.metrolist.music.ui.screens.settings

import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.lyrics.overlay.OverlayLyricsEngine
import com.metrolist.music.lyrics.overlay.OverlayLyricsPreferences
import com.metrolist.music.lyrics.overlay.OverlayLyricsService
import com.metrolist.music.lyrics.vault.DatabaseProvider
import com.metrolist.music.lyrics.vault.LyricFileInfo
import com.metrolist.music.lyrics.vault.LyricsSyncManager
import com.metrolist.music.lyrics.vault.VaultManager
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayLyricsSettingsScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val enabled by OverlayLyricsPreferences.getOverlayEnabled(context).collectAsState(initial = false)
    val fontSize by OverlayLyricsPreferences.getOverlayFontSize(context).collectAsState(initial = 18f)

    val currentVideoId by OverlayLyricsService.currentVideoId.collectAsState()

    var showVersionDialog by remember { mutableStateOf(false) }
    var availableVersions by remember { mutableStateOf<List<LyricFileInfo>>(emptyList()) }
    var activeFilename by remember { mutableStateOf<String?>(null) }
    var parsedVaultDir by remember { mutableStateOf<File?>(null) }
    
    val lineAnimation by OverlayLyricsPreferences.getLineAnimation(context).collectAsState(initial = 2)
    val wordAnimation by OverlayLyricsPreferences.getWordAnimation(context).collectAsState(initial = 2)
    val activeWordColor by OverlayLyricsPreferences.getActiveWordColor(context).collectAsState(initial = android.graphics.Color.parseColor("#FFD700"))
    val activeLineColor by OverlayLyricsPreferences.getActiveLineColor(context).collectAsState(initial = android.graphics.Color.WHITE)
    val inactiveLineColor by OverlayLyricsPreferences.getInactiveLineColor(context).collectAsState(initial = android.graphics.Color.parseColor("#A6FFFFFF"))
    
    var showLineAnimationDialog by remember { mutableStateOf(false) }
    var showWordAnimationDialog by remember { mutableStateOf(false) }
    var activeColorDialog by remember { mutableStateOf<String?>(null) }
    
    val colorPalette = remember {
        listOf(
            android.graphics.Color.WHITE,
            android.graphics.Color.parseColor("#FFD700"),
            android.graphics.Color.parseColor("#00E5FF"),
            android.graphics.Color.parseColor("#FF4081"),
            android.graphics.Color.parseColor("#76FF03"),
            android.graphics.Color.parseColor("#A6FFFFFF")
        )
    }

    LaunchedEffect(currentVideoId) {
        if (currentVideoId.isBlank()) {
            availableVersions = emptyList()
            activeFilename = null
            parsedVaultDir = null
            return@LaunchedEffect
        }
        
        withContext(Dispatchers.IO) {
            val masterPath = LyricsSyncManager.getMasterFolderPath()
            val dao = DatabaseProvider.getDatabase(context, masterPath).libraryDao()
            val index = dao.getSongIndexByVideoId(currentVideoId) ?: return@withContext
            val dir = File(index.folderPath)
            if (!dir.exists()) return@withContext

            val vaultManager = VaultManager(masterPath)
            val metadata = vaultManager.readMetadataJson(dir)
            
            withContext(Dispatchers.Main) {
                parsedVaultDir = dir
                activeFilename = index.activeLyricFile
                if (metadata != null) {
                    availableVersions = metadata.lyrics
                } else {
                    availableVersions = emptyList()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.overlay_lyrics)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.back_button_desc)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Engine Controls Section
            Material3SettingsGroup(
                title = stringResource(R.string.overlay_lyrics),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.lyrics),
                        title = { Text(stringResource(R.string.overlay_lyrics_master_toggle)) },
                        description = { Text(stringResource(R.string.overlay_lyrics_master_toggle_desc)) },
                        trailingContent = {
                            Switch(
                                checked = enabled,
                                onCheckedChange = { newValue ->
                                    coroutineScope.launch {
                                        OverlayLyricsPreferences.setOverlayEnabled(context, newValue)
                                        if (newValue) {
                                            if (!Settings.canDrawOverlays(context)) {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.overlay_permission_required_desc),
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                            OverlayLyricsService.start(context)
                                        } else {
                                            OverlayLyricsService.stop(context)
                                        }
                                    }
                                }
                            )
                        }
                    )
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Animations Section
            Material3SettingsGroup(
                title = stringResource(R.string.customize_animations),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.tune), // assuming a generic icon, we can use tune or play_circle
                        title = { Text(stringResource(R.string.overlay_lyrics_line_animation)) },
                        description = { Text(stringResource(R.string.overlay_lyrics_line_animation_desc)) },
                        onClick = { showLineAnimationDialog = true }
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.tune),
                        title = { Text(stringResource(R.string.overlay_lyrics_word_animation)) },
                        description = { Text(stringResource(R.string.overlay_lyrics_word_animation_desc)) },
                        onClick = { showWordAnimationDialog = true }
                    )
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Colors Section
            Material3SettingsGroup(
                title = stringResource(R.string.customize_colors),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.palette), // assuming palette exists
                        title = { Text(stringResource(R.string.overlay_lyrics_active_word_color)) },
                        description = { Text("Choose highlight color for the active word") },
                        onClick = { activeColorDialog = "activeWord" }
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.palette),
                        title = { Text(stringResource(R.string.overlay_lyrics_active_line_color)) },
                        description = { Text("Choose color for the active line") },
                        onClick = { activeColorDialog = "activeLine" }
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.palette),
                        title = { Text(stringResource(R.string.overlay_lyrics_inactive_line_color)) },
                        description = { Text("Choose color for the inactive lines") },
                        onClick = { activeColorDialog = "inactiveLine" }
                    )
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Customization Section
            Material3SettingsGroup(
                title = stringResource(R.string.customize_colors),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.sliders),
                        title = { Text(stringResource(R.string.overlay_lyrics_font_size)) },
                        description = {
                            Column {
                                Text("${fontSize.toInt()} sp")
                                Slider(
                                    value = fontSize,
                                    onValueChange = { newSize ->
                                        coroutineScope.launch {
                                            OverlayLyricsPreferences.setOverlayFontSize(context, newSize)
                                        }
                                    },
                                    valueRange = 12f..36f,
                                    steps = 24
                                )
                            }
                        }
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.tune),
                        title = { Text(stringResource(R.string.overlay_lyrics_version_selector)) },
                        description = { Text(stringResource(R.string.overlay_lyrics_version_selector_desc)) },
                        onClick = {
                            if (currentVideoId.isBlank()) {
                                Toast.makeText(context, context.getString(R.string.no_song_playing), Toast.LENGTH_SHORT).show()
                            } else {
                                showVersionDialog = true
                            }
                        }
                    )
                )
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            val prefetchEnabled by OverlayLyricsPreferences.getLyricsPrefetchEnabled(context).collectAsState(initial = false)
            val prefetchCount by OverlayLyricsPreferences.getLyricsPrefetchCount(context).collectAsState(initial = 1)

            Material3SettingsGroup(
                title = "Database Builder",
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.download),
                        title = { Text("Pre-download upcoming lyrics") },
                        description = { Text("Automatically downloads lyrics for upcoming songs in the background") },
                        trailingContent = {
                            Switch(
                                checked = prefetchEnabled,
                                onCheckedChange = { newValue ->
                                    coroutineScope.launch {
                                        OverlayLyricsPreferences.setLyricsPrefetchEnabled(context, newValue)
                                    }
                                }
                            )
                        }
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.queue_music),
                        title = { Text("Songs to pre-download") },
                        description = {
                            Column {
                                Text("$prefetchCount songs", color = if (prefetchEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                                Slider(
                                    value = prefetchCount.toFloat(),
                                    onValueChange = { newSize ->
                                        coroutineScope.launch {
                                            OverlayLyricsPreferences.setLyricsPrefetchCount(context, newSize.toInt())
                                        }
                                    },
                                    valueRange = 1f..20f,
                                    steps = 18,
                                    enabled = prefetchEnabled
                                )
                            }
                        }
                    )
                )
            )
        }

        if (showVersionDialog) {
            AlertDialog(
                onDismissRequest = { showVersionDialog = false },
                title = { Text(stringResource(R.string.overlay_lyrics_version_selector)) },
                text = {
                    if (availableVersions.isEmpty()) {
                        Text(stringResource(R.string.no_versions_found))
                    } else {
                        Column {
                            availableVersions.forEach { version ->
                                val isSelected = version.filename == activeFilename
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val dir = parsedVaultDir ?: return@launch
                                                val masterPath = LyricsSyncManager.getMasterFolderPath()
                                                val vaultManager = VaultManager(masterPath)
                                                val dao = DatabaseProvider.getDatabase(context, masterPath).libraryDao()

                                                // 1. Instantly update UI / LRU Cache
                                                OverlayLyricsEngine.loadDirectLyricFile(
                                                    context = context,
                                                    videoId = currentVideoId,
                                                    folderPath = dir.absolutePath,
                                                    activeLyricFile = version.filename
                                                )

                                                // 2. Update Room DB activeLyricFile
                                                dao.updateActiveLyricFile(currentVideoId, version.filename)

                                                // 3. Asynchronously overwrite metadata.json setting Rank 0 under Mutex lock
                                                vaultManager.updateLyricFileRankOverride(dir, version.filename)

                                                withContext(Dispatchers.Main) {
                                                    activeFilename = version.filename
                                                    showVersionDialog = false
                                                }
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = null
                                    )
                                    Column(modifier = Modifier.padding(start = 12.dp)) {
                                        Text(
                                            text = "${version.provider} (${version.type})",
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = "Format: ${version.format} | Rank: ${version.rank}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showVersionDialog = false }) {
                        Text(stringResource(R.string.close))
                    }
                }
            )
        }

        if (showLineAnimationDialog) {
            AlertDialog(
                onDismissRequest = { showLineAnimationDialog = false },
                title = { Text(stringResource(R.string.overlay_lyrics_line_animation)) },
                text = {
                    Column {
                        val options = listOf("None", "Fade", "Slide Up", "Scale/Zoom", "Blur")
                        options.forEachIndexed { index, option ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    coroutineScope.launch { OverlayLyricsPreferences.setLineAnimation(context, index) }
                                    showLineAnimationDialog = false
                                }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = lineAnimation == index, onClick = null)
                                Text(text = option, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showLineAnimationDialog = false }) { Text(stringResource(R.string.close)) } }
            )
        }

        if (showWordAnimationDialog) {
            AlertDialog(
                onDismissRequest = { showWordAnimationDialog = false },
                title = { Text(stringResource(R.string.overlay_lyrics_word_animation)) },
                text = {
                    Column {
                        val options = listOf("None", "Color Fill", "Spring Scale", "Glow")
                        options.forEachIndexed { index, option ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    coroutineScope.launch { OverlayLyricsPreferences.setWordAnimation(context, index) }
                                    showWordAnimationDialog = false
                                }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = wordAnimation == index, onClick = null)
                                Text(text = option, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showWordAnimationDialog = false }) { Text(stringResource(R.string.close)) } }
            )
        }

        if (activeColorDialog != null) {
            AlertDialog(
                onDismissRequest = { activeColorDialog = null },
                title = { Text("Choose Color") },
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        colorPalette.forEach { colorInt ->
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(colorInt))
                                    .clickable {
                                        coroutineScope.launch {
                                            when (activeColorDialog) {
                                                "activeWord" -> OverlayLyricsPreferences.setActiveWordColor(context, colorInt)
                                                "activeLine" -> OverlayLyricsPreferences.setActiveLineColor(context, colorInt)
                                                "inactiveLine" -> OverlayLyricsPreferences.setInactiveLineColor(context, colorInt)
                                            }
                                            activeColorDialog = null
                                        }
                                    }
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { activeColorDialog = null }) { Text(stringResource(R.string.close)) } }
            )
        }
    }
}
