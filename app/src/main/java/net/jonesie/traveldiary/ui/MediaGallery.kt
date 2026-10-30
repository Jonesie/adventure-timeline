package net.jonesie.traveldiary.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import net.jonesie.traveldiary.data.MediaItem
import net.jonesie.traveldiary.data.MediaType
import net.jonesie.traveldiary.data.TripDetail
import java.text.DateFormat
import java.util.Date

/** Chronological order, shared by the grid and the viewer so a tapped tile opens at the right page. */
fun TripDetail.sortedMedia(): List<MediaItem> = media.sortedBy { it.timestamp }

/** Coil loader that can also pull a preview frame out of a video. */
@Composable
fun rememberMediaImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember { ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.build() }
}

@Composable
fun MediaTab(
    trip: TripDetail,
    progress: MediaImportProgress?,
    onPickMedia: () -> Unit,
    onAutoAddMedia: () -> Unit,
    onOpen: (Int) -> Unit,
    nearbyCount: Int? = null,
    onReviewNearby: () -> Unit = {}
) {
    val items = remember(trip.media) { trip.sortedMedia() }
    val imageLoader = rememberMediaImageLoader()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onPickMedia, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text("From phone", maxLines = 1)
            }
            OutlinedButton(onClick = onAutoAddMedia, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text("From camera", maxLines = 1)
            }
        }

        if (nearbyCount != null && nearbyCount > 0) {
            androidx.compose.material3.Card(
                onClick = onReviewNearby,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        "$nearbyCount photo${if (nearbyCount == 1) "" else "s"} taken along this route aren't in any trip.",
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.TextButton(onClick = onReviewNearby) { Text("Review") }
                }
            }
        }

        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Importing ${progress.done} of ${progress.total}…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        if (items.isEmpty()) {
            Text(
                "No photos, videos or audio yet. Use the camera buttons below, pick files from your phone, " +
                    "or tap “From camera” to add everything your camera shot during this trip.",
                modifier = Modifier.padding(top = 16.dp)
            )
        } else {
            Text(
                "${items.count { it.type == MediaType.PHOTO }} photos · " +
                    "${items.count { it.type == MediaType.VIDEO }} videos · " +
                    "${items.count { it.type == MediaType.AUDIO }} audio",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    MediaTile(item, imageLoader, onClick = { onOpen(index) })
                }
            }
        }
    }
}

@Composable
internal fun MediaTile(item: MediaItem, imageLoader: ImageLoader, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (item.type == MediaType.AUDIO) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.AudioFile, contentDescription = "Audio", modifier = Modifier.padding(bottom = 4.dp))
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.timestamp)),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            AsyncImage(
                model = Uri.parse(item.uri),
                imageLoader = imageLoader,
                contentDescription = item.type.name.lowercase(),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (item.type == MediaType.VIDEO) {
                Box(
                    modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(6.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Video", tint = Color.White)
                }
            }
        }
    }
}
