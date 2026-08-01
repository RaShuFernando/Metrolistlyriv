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

    fun loadCurrentTrackMetadata() {
        coroutineScope.launch(Dispatchers.IO) {
            if (currentVideoId.isBlank()) return@launch
            val masterPath = LyricsSyncManager.getMasterFolderPath()
            val dao = DatabaseProvider.getDatabase(context, masterPath).libraryDao()
            val index = dao.getSongIndexByVideoId(currentVideoId) ?: return@launch
            val dir = File(index.folderPath)
            if (!dir.exists()) return@launch

            parsedVaultDir = dir
            activeFilename = index.activeLyricFile

            val vaultManager = VaultManager(masterPath)
            val metadata = vaultManager.readMetadataJson(dir)
            if (metadata != null) {
                withContext(Dispatchers.Main) {
                    availableVersions = metadata.lyrics
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
                                loadCurrentTrackMetadata()
                                showVersionDialog = true
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
    }
}
