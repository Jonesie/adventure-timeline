package net.jonesie.traveldiary.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

private const val WEBSITE_URL = "https://jonesie.github.io/adventure-timeline/"
private const val SOURCE_URL = "https://github.com/Jonesie/adventure-timeline"
private const val PRIVACY_URL = "https://jonesie.github.io/adventure-timeline/privacy/"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            Text("Adventure Timeline", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
            Text(
                "A private diary of the places you've been.",
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(top = 4.dp)
            )

            Heading("What it is")
            Body(
                "Adventure Timeline keeps a record of your trips: where you went, how you got there, who was with you, and the photos, " +
                    "videos, voice notes and impressions you picked up along the way. Pull in the trips Google Maps Timeline has already " +
                    "found, then add the human side to them: a rating, how hard or enjoyable each leg was, a note about the view."
            )
            Body(
                "Open the map to see every trip you've taken at once. Tap a route to open the trip, and filter by how you travelled, " +
                    "whether that was on a motorbike, in a car or on foot. Ask “have I been here before?” and it will tell you which " +
                    "trips passed this way. Log an incident the moment something goes wrong, a spill, a wrong turn, a river crossing, and it " +
                    "will join the right trip once it's imported. Turn a trip into a picture or PDF to share with whoever you choose."
            )

            Heading("Why I made it")
            Body(
                "I wanted a backup for my own memory of the places I've travelled: somewhere I could look back and see the route, " +
                    "remember who came along, and find the photo I took at that gate or that lookout. I didn't want something that " +
                    "watches everything I do online, so this is about travel only."
            )
            Body(
                "It has to work with no signal, on the road, and the data had to be mine. Everything lives on your phone, with no " +
                    "account to create and nothing sent to anyone. A backup is a file you save wherever you like."
            )

            Heading("Your data stays yours")
            listOf(
                "No ads, no tracking, no accounts.",
                "Works offline. The only network use is map tiles and place-name lookups.",
                "Backups and sharing happen only when you ask.",
                "Free software under the GPL-3.0, so you can read, change and share the code."
            ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp)) }

            Heading("Credits")
            Body(
                "Maps © OpenStreetMap contributors. Built with Kotlin, Jetpack Compose, Room, osmdroid and Coil. " +
                    "Thank you to all the free software it stands on. Adventure Timeline is an independent project and is not " +
                    "affiliated with or endorsed by Google. “Google” and “Google Maps Timeline” are trademarks of Google LLC."
            )

            Heading("Links")
            LinkText("Website and user guide", onClick = { open(WEBSITE_URL) })
            LinkText("Source code and issues", onClick = { open(SOURCE_URL) })
            LinkText("Privacy policy", onClick = { open(PRIVACY_URL) })

            CoffeeButton()

            version?.let {
                Text(
                    "Version $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)
    )
}
