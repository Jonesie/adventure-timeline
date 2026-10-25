package net.jonesie.traveldiary

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import net.jonesie.traveldiary.data.MediaType
import net.jonesie.traveldiary.ui.AddImpressionScreen
import net.jonesie.traveldiary.ui.AddLegScreen
import net.jonesie.traveldiary.ui.AddTripScreen
import net.jonesie.traveldiary.ui.EditTripScreen
import net.jonesie.traveldiary.ui.FullScreenMapScreen
import net.jonesie.traveldiary.ui.ImportTimelineScreen
import net.jonesie.traveldiary.ui.NearbyScreen
import net.jonesie.traveldiary.ui.RecordAudioScreen
import net.jonesie.traveldiary.ui.TravelDiaryViewModel
import net.jonesie.traveldiary.ui.TripDetailScreen
import net.jonesie.traveldiary.ui.TripListScreen
import net.jonesie.traveldiary.util.LocationHelper
import net.jonesie.traveldiary.util.MediaFiles

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            androidx.compose.material3.MaterialTheme {
                TravelDiaryApp()
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TravelDiaryApp() {
    val navController = rememberNavController()
    val viewModel: TravelDiaryViewModel = viewModel()
    val context = LocalContext.current

    NavHost(navController = navController, startDestination = "tripList") {
        composable("tripList") {
            TripListScreen(
                viewModel = viewModel,
                onAddTrip = { navController.navigate("addTrip") },
                onOpenTrip = { tripId -> navController.navigate("tripDetail/$tripId") },
                onNearby = { navController.navigate("nearby") },
                onImportTimeline = { navController.navigate("importTimeline") }
            )
        }

        composable("importTimeline") {
            ImportTimelineScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onImported = { navController.popBackStack() }
            )
        }

        composable("addTrip") {
            AddTripScreen(
                onBack = { navController.popBackStack() },
                onSave = { title, notes, companions, startDate, endDate ->
                    viewModel.addTrip(
                        title = title,
                        startDate = startDate,
                        endDate = endDate,
                        notes = notes,
                        companionNames = companions
                    ) { tripId ->
                        navController.popBackStack()
                        navController.navigate("tripDetail/$tripId")
                    }
                }
            )
        }

        composable("nearby") {
            NearbyScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(
            "tripDetail/{tripId}",
            arguments = listOf(navArgument("tripId") { type = NavType.LongType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getLong("tripId") ?: return@composable
            var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }

            val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                if (success) {
                    val uri = pendingPhotoUri
                    if (uri != null) {
                        val loc = LocationHelper.lastKnownLocation(context)
                        viewModel.addMedia(tripId, null, MediaType.PHOTO, uri.toString(), "", loc?.latitude, loc?.longitude)
                    }
                }
            }
            fun launchCapture() {
                val uri = MediaFiles.newPhotoUri(context)
                pendingPhotoUri = uri
                takePicture.launch(uri)
            }
            val requestCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) launchCapture()
            }
            val importPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    viewModel.addMedia(tripId, null, MediaType.PHOTO, uri.toString(), "", null, null)
                }
            }
            val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    viewModel.addMedia(tripId, null, MediaType.AUDIO, uri.toString(), "", null, null)
                }
            }

            TripDetailScreen(
                tripId = tripId,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onAddLeg = { navController.navigate("tripDetail/$tripId/addLeg") },
                onCapturePhoto = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        launchCapture()
                    } else {
                        requestCameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                onImportPhoto = { importPhoto.launch("image/*") },
                onRecordAudio = { navController.navigate("tripDetail/$tripId/recordAudio") },
                onImportAudio = { importAudio.launch("audio/*") },
                onAddImpression = { navController.navigate("tripDetail/$tripId/addImpression") },
                onEdit = { navController.navigate("tripDetail/$tripId/edit") },
                onOpenMap = { navController.navigate("tripDetail/$tripId/map") }
            )
        }

        composable(
            "tripDetail/{tripId}/map",
            arguments = listOf(navArgument("tripId") { type = NavType.LongType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getLong("tripId") ?: return@composable
            FullScreenMapScreen(tripId = tripId, viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(
            "tripDetail/{tripId}/edit",
            arguments = listOf(navArgument("tripId") { type = NavType.LongType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getLong("tripId") ?: return@composable
            EditTripScreen(
                tripId = tripId,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(
            "tripDetail/{tripId}/addLeg",
            arguments = listOf(navArgument("tripId") { type = NavType.LongType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getLong("tripId") ?: return@composable
            AddLegScreen(
                onBack = { navController.popBackStack() },
                onSave = { transportType, origin, destination ->
                    viewModel.addLeg(tripId, transportType, origin, destination, System.currentTimeMillis(), null)
                    navController.popBackStack()
                }
            )
        }

        composable(
            "tripDetail/{tripId}/addImpression",
            arguments = listOf(navArgument("tripId") { type = NavType.LongType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getLong("tripId") ?: return@composable
            AddImpressionScreen(
                onBack = { navController.popBackStack() },
                onSave = { text ->
                    val loc = LocationHelper.lastKnownLocation(context)
                    viewModel.addImpression(tripId, null, text, loc?.latitude, loc?.longitude)
                    navController.popBackStack()
                }
            )
        }

        composable(
            "tripDetail/{tripId}/recordAudio",
            arguments = listOf(navArgument("tripId") { type = NavType.LongType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getLong("tripId") ?: return@composable
            RecordAudioScreen(
                onBack = { navController.popBackStack() },
                onSaved = { file ->
                    val uri = MediaFiles.audioUri(context, file)
                    viewModel.addMedia(tripId, null, MediaType.AUDIO, uri.toString(), "", null, null)
                    navController.popBackStack()
                }
            )
        }
    }
}
