package net.jonesie.traveldiary.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun EditTripScreen(
    tripId: Long,
    viewModel: TravelDiaryViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val detail by remember(tripId) { viewModel.tripDetail(tripId) }.collectAsState(initial = null)
    val trip = detail

    if (trip == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    AddTripScreen(
        onBack = onBack,
        onSave = { title, notes, companions, startDate, endDate ->
            viewModel.updateTrip(tripId, title, notes, companions, startDate, endDate, onSaved)
        },
        initialTitle = trip.trip.title,
        initialNotes = trip.trip.notes,
        initialCompanions = trip.companions.joinToString(", ") { it.name },
        initialStartDate = trip.trip.startDate,
        initialEndDate = trip.trip.endDate,
        screenTitle = "Edit trip",
        saveLabel = "Save changes"
    )
}
