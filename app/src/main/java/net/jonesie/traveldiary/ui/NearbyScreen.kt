package net.jonesie.traveldiary.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.jonesie.traveldiary.util.LocationHelper
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(viewModel: TravelDiaryViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(LocationHelper.hasPermission(context)) }
    var result by remember { mutableStateOf<NearbyResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return@LaunchedEffect
        }
        loading = true
        val location = LocationHelper.lastKnownLocation(context)
        result = if (location != null) viewModel.findNearby(location.latitude, location.longitude) else null
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Have I been here before?") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            when {
                !hasPermission -> Text("Location permission needed to check nearby visits.")
                loading -> CircularProgressIndicator()
                result == null -> Text("Couldn't get a current location yet — try again outdoors or with wifi/GPS on.")
                result!!.isEmpty -> Text("No earlier visits logged near here.")
                else -> {
                    Text("Yes — found earlier visits nearby:")
                    result!!.impressions.forEach {
                        Text("• ${DateFormat.getDateInstance().format(Date(it.timestamp))}: ${it.text}")
                    }
                    result!!.media.forEach {
                        Text("• ${DateFormat.getDateInstance().format(Date(it.timestamp))}: ${it.type} ${it.caption}")
                    }
                }
            }
        }
    }
}
