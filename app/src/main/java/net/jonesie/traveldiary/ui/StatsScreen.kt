package net.jonesie.traveldiary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.jonesie.traveldiary.util.TripStats
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private fun km(value: Double) = String.format(Locale.getDefault(), "%,.0f km", value)
private fun hours(value: Double) = if (value >= 100) String.format(Locale.getDefault(), "%,.0f h", value) else String.format(Locale.getDefault(), "%.1f h", value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: TravelDiaryViewModel, onBack: () -> Unit, onOpenTrip: (Long) -> Unit) {
    var stats by remember { mutableStateOf<TripStats?>(null) }
    LaunchedEffect(Unit) { stats = viewModel.tripStats() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip stats") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        val s = stats
        if (s == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        if (s.trips == 0) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No trips yet, so nothing to count.")
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Summary(s)
            if (s.byTransport.isNotEmpty()) TransportSection(s)
            if (s.byYear.isNotEmpty()) YearSection(s)
            if (s.byIncident.isNotEmpty()) IncidentSection(s)
            RatingSection(s)
            Highlights(s, onOpenTrip)
            Text(
                "Distances are approximate: they're added up from recorded GPS points, with flights as a straight line between airports.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(s: TripStats) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile("Trips", "${s.trips}")
        Tile("Days on the road", "${s.daysOnTheRoad}")
        Tile("Distance", km(s.totalKm))
        Tile("Legs", "${s.legs}")
        Tile("Photos", "${s.photos}")
        Tile("Videos", "${s.videos}")
        Tile("Voice notes", "${s.voiceNotes}")
        Tile("Notes", "${s.notes}")
        Tile("Incidents", "${s.incidents}")
    }
}

@Composable
private fun Tile(label: String, value: String) {
    Card(modifier = Modifier.width(112.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
}

/** A label, a bar scaled against [max], and a value, on one row. */
@Composable
private fun BarRow(label: @Composable () -> Unit, fraction: Float, color: Color, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(96.dp)) { label() }
        Box(modifier = Modifier.weight(1f).height(14.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(14.dp)
                    .clip(RoundedCornerShape(4.dp)).background(color)
            )
        }
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp).width(84.dp))
    }
}

@Composable
private fun TransportSection(s: TripStats) {
    Column {
        SectionTitle("By transport")
        val maxKm = s.byTransport.maxOf { it.km }.coerceAtLeast(1.0)
        s.byTransport.forEach { t ->
            BarRow(
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(transportIcon(t.type), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(t.type.displayName(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 6.dp))
                    }
                },
                fraction = (t.km / maxKm).toFloat(),
                color = MaterialTheme.colorScheme.primary,
                value = if (t.km > 0) km(t.km) else "${t.legs} legs"
            )
            if (t.hours > 0) {
                Text(
                    "${t.legs} legs · ${hours(t.hours)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 96.dp, bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun IncidentSection(s: TripStats) {
    Column {
        SectionTitle("Incidents")
        val max = s.byIncident.maxOf { it.count }.coerceAtLeast(1)
        s.byIncident.forEach { stat ->
            BarRow(
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(stat.category.icon(), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stat.category.label(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 6.dp))
                    }
                },
                fraction = stat.count.toFloat() / max,
                color = MaterialTheme.colorScheme.error,
                value = "${stat.count}"
            )
        }
        if (s.seriousIncidents > 0) {
            Text(
                "${s.seriousIncidents} serious",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun YearSection(s: TripStats) {
    Column {
        SectionTitle("By year")
        val maxKm = s.byYear.maxOf { it.km }.coerceAtLeast(1.0)
        s.byYear.forEach { y ->
            BarRow(
                label = { Text("${y.year}", style = MaterialTheme.typography.bodyMedium) },
                fraction = (y.km / maxKm).toFloat(),
                color = MaterialTheme.colorScheme.tertiary,
                value = km(y.km)
            )
            Text(
                "${y.trips} trip" + if (y.trips == 1) "" else "s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 96.dp, bottom = 2.dp)
            )
        }
    }
}

@Composable
private fun RatingSection(s: TripStats) {
    Column {
        SectionTitle("Ratings")
        val max = s.ratingCounts.drop(1).maxOrNull()?.coerceAtLeast(1) ?: 1
        for (stars in 5 downTo 1) {
            val count = s.ratingCounts[stars]
            BarRow(
                label = { Text(starString(stars), style = MaterialTheme.typography.bodyMedium) },
                fraction = if (count == 0) 0f else count.toFloat() / max,
                color = ratingColor(stars) ?: MaterialTheme.colorScheme.primary,
                value = "$count"
            )
        }
        val unrated = s.ratingCounts[0]
        if (unrated > 0) Text("$unrated not rated yet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val facts = listOfNotNull(
            s.averageRating?.let { "Average trip rating %.1f ★".format(it) },
            s.averageEnjoyment?.let { "Average leg enjoyment %.1f / 5".format(it) },
            s.averageDifficulty?.let { "Average leg difficulty %.1f / 5".format(it) }
        )
        facts.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun Highlights(s: TripStats, onOpenTrip: (Long) -> Unit) {
    if (s.longestTrip == null && s.topRated.isEmpty()) return
    val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
    Column {
        SectionTitle("Highlights")
        s.longestTrip?.let { longest ->
            HighlightRow("Longest trip", longest.trip.title, "${km(longest.km)} · ${dates.format(Date(longest.trip.startDate))}") { onOpenTrip(longest.trip.id) }
        }
        s.topRated.forEach { trip ->
            HighlightRow(starString(trip.rating), trip.title, dates.format(Date(trip.startDate))) { onOpenTrip(trip.id) }
        }
    }
}

@Composable
private fun HighlightRow(tag: String, title: String, detail: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
