package net.jonesie.traveldiary.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.jonesie.traveldiary.R

/**
 * Shown in place of the main tabs while the diary is completely empty: what the app is for, the
 * quickest ways to get something in, and a link to the full guide. It disappears on its own as soon
 * as there is a trip or an incident.
 */
@Composable
fun GettingStartedScreen(
    onImportTimeline: () -> Unit,
    onAddTrip: () -> Unit,
    onLogIncident: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.splash_icon),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(140.dp).clip(RoundedCornerShape(28.dp))
            )
            Text(
                "Welcome to Adventure Timeline",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp)
            )
            Text(
                "A private diary of the places you've been. It's empty, so here's how to start.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )

            Column(modifier = Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Step(
                    Icons.Filled.CloudDownload, "Bring in your trips",
                    "Import a Timeline.json exported from Google Maps Timeline, and your trips, legs and routes appear. Or add a trip by hand."
                )
                Step(
                    Icons.Filled.AddPhotoAlternate, "Add the human side",
                    "Attach photos, videos and voice notes, rate each leg, and note who came along."
                )
                Step(
                    Icons.Filled.ReportProblem, "Log incidents as they happen",
                    "A spill, a wrong turn, a river crossing gone sideways. Log it on the road, even before the trip exists. It joins the right trip later."
                )
                Step(
                    Icons.Filled.Map, "Look back",
                    "See every trip on one map, ask \"have I been here before?\", and turn a trip into a picture or PDF to share."
                )
            }

            Button(onClick = onImportTimeline, modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(top = 28.dp)) {
                Text("Import from Google Timeline")
            }
            OutlinedButton(onClick = onAddTrip, modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)) {
                Text("Add a trip by hand")
            }
            OutlinedButton(onClick = onLogIncident, modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)) {
                Text("Log an incident")
            }
            TextButton(onClick = onOpenSettings) { Text("Restore from a backup in Settings") }
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL))) } }) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Full help and user guide", modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                "Everything stays on your phone. No account, no ads, no tracking.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp).size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
