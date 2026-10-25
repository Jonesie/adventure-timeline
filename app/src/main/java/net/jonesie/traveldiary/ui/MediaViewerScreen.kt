package net.jonesie.traveldiary.ui

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import coil.compose.AsyncImage
import net.jonesie.traveldiary.data.MediaItem
import net.jonesie.traveldiary.data.MediaType
import java.text.DateFormat
import java.util.Date

@Composable
fun MediaViewerScreen(tripId: Long, startIndex: Int, viewModel: TravelDiaryViewModel, onBack: () -> Unit) {
    val detail by remember(tripId) { viewModel.tripDetail(tripId) }.collectAsState(initial = null)
    val trip = detail
    if (trip == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    MediaViewer(
        items = trip.sortedMedia(),
        startIndex = startIndex,
        onDelete = { viewModel.deleteMedia(it) },
        onBack = onBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaViewer(items: List<MediaItem>, startIndex: Int, onDelete: (MediaItem) -> Unit, onBack: () -> Unit) {
    val latestItems by rememberUpdatedState(items)
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { latestItems.size }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) onBack() }
    if (items.isEmpty()) return

    val current = items[pagerState.currentPage.coerceIn(0, items.size - 1)]
    val imageLoader = rememberMediaImageLoader()

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("${pagerState.currentPage + 1} / ${items.size}") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove from trip")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxSize()) { page ->
                items.getOrNull(page)?.let { MediaPage(it, imageLoader) }
            }
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(current.timestamp)),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove from this trip?") },
            text = {
                Text("It's removed from the diary only. Photos and videos from your camera roll stay on your phone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(current)
                    confirmDelete = false
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MediaPage(item: MediaItem, imageLoader: coil.ImageLoader) {
    val uri = Uri.parse(item.uri)
    var playing by remember(item.id) { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            item.type == MediaType.PHOTO -> AsyncImage(
                model = uri,
                imageLoader = imageLoader,
                contentDescription = "photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            playing -> InAppPlayer(uri, isVideo = item.type == MediaType.VIDEO)
            else -> {
                if (item.type == MediaType.VIDEO) {
                    AsyncImage(
                        model = uri,
                        imageLoader = imageLoader,
                        contentDescription = "video preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Filled.AudioFile, contentDescription = "audio", tint = Color.White, modifier = Modifier.padding(48.dp))
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable { playing = true }
                        .padding(20.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White)
                }
            }
        }
    }
}

private fun formatMs(ms: Int): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

/**
 * Plays video or audio with MediaPlayer. Video is drawn on a TextureView rather than the
 * SurfaceView inside VideoView: a SurfaceView sits behind the window, so the viewer's opaque
 * black background covered it and playback looked like a black screen.
 */
@Composable
private fun InAppPlayer(uri: Uri, isVideo: Boolean) {
    val context = LocalContext.current
    val player = remember(uri) { MediaPlayer() }
    var prepared by remember(uri) { mutableStateOf(false) }
    var playing by remember(uri) { mutableStateOf(false) }
    var failed by remember(uri) { mutableStateOf(false) }
    var duration by remember(uri) { mutableIntStateOf(0) }
    var position by remember(uri) { mutableIntStateOf(0) }
    var videoRatio by remember(uri) { mutableFloatStateOf(16f / 9f) }

    DisposableEffect(player) {
        player.setOnPreparedListener {
            duration = it.duration
            prepared = true
            it.start()
            playing = true
        }
        player.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) videoRatio = w.toFloat() / h }
        player.setOnCompletionListener { playing = false; position = duration }
        player.setOnErrorListener { _, _, _ -> failed = true; true }
        runCatching {
            player.setDataSource(context, uri)
            player.prepareAsync()
        }.onFailure { failed = true }
        onDispose { runCatching { player.release() } }
    }

    LaunchedEffect(player) {
        while (true) {
            if (prepared && playing) position = runCatching { player.currentPosition }.getOrDefault(position)
            delay(250)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isVideo) {
                AndroidView(
                    modifier = Modifier.aspectRatio(videoRatio),
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                private var surface: Surface? = null
                                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                    surface = Surface(texture)
                                    runCatching { player.setSurface(surface) }
                                }
                                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
                                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                    runCatching { player.setSurface(null) }
                                    surface?.release()
                                    surface = null
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                            }
                        }
                    }
                )
            } else {
                Icon(Icons.Filled.AudioFile, contentDescription = "audio", tint = Color.White, modifier = Modifier.padding(48.dp))
            }
            when {
                failed -> Text("Couldn't play this file", color = Color.White)
                !prepared -> CircularProgressIndicator()
            }
        }
        if (prepared) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (playing) {
                        player.pause()
                        playing = false
                    } else {
                        if (position >= duration) player.seekTo(0)
                        player.start()
                        playing = true
                    }
                }) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = Color.White
                    )
                }
                Slider(
                    value = position.toFloat(),
                    onValueChange = {
                        position = it.toInt()
                        player.seekTo(it.toInt())
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${formatMs(position)} / ${formatMs(duration)}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}
