package net.jonesie.traveldiary

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import net.jonesie.traveldiary.data.MediaType
import net.jonesie.traveldiary.ui.AddImpressionScreen
import net.jonesie.traveldiary.ui.AddLegScreen
import net.jonesie.traveldiary.ui.AddTripScreen
import net.jonesie.traveldiary.ui.EditTripScreen
import net.jonesie.traveldiary.ui.FullScreenMapScreen
import net.jonesie.traveldiary.ui.ImportTimelineScreen
import net.jonesie.traveldiary.ui.MediaPickerScreen
import net.jonesie.traveldiary.ui.MediaViewerScreen
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
            var pendingVideoUri by remember { mutableStateOf<Uri?>(null) }
            var afterCameraPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
            val scope = rememberCoroutineScope()

            val captureVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
                val uri = pendingVideoUri
                if (success && uri != null) {
                    val loc = LocationHelper.lastKnownLocation(context)
                    viewModel.addMedia(tripId, null, MediaType.VIDEO, uri.toString(), "", loc?.latitude, loc?.longitude)
                }
            }
            val requestCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) afterCameraPermission?.invoke()
                afterCameraPermission = null
            }
            fun withCamera(action: () -> Unit) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    action()
                } else {
                    afterCameraPermission = action
                    requestCameraPermission.launch(Manifest.permission.CAMERA)
                }
            }
            fun launchPhoto() {
                val uri = MediaFiles.newPhotoUri(context)
                pendingPhotoUri = uri
                takePicture.launch(uri)
            }
            fun launchVideo() {
                val uri = MediaFiles.newVideoUri(context)
                pendingVideoUri = uri
                captureVideo.launch(uri)
            }

            fun reportImport(failed: Int) {
                if (failed > 0) Toast.makeText(context, "$failed file(s) couldn't be imported", Toast.LENGTH_LONG).show()
            }
            val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
                viewModel.importFiles(tripId, uris, null) { _, failed -> reportImport(failed) }
            }
            val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) viewModel.importFiles(tripId, listOf(uri), MediaType.AUDIO) { _, failed -> reportImport(failed) }
            }

            fun runAutoAdd() {
                scope.launch {
                    val (photos, videos) = viewModel.autoAddCameraMedia(tripId)
                    val message = if (photos + videos == 0) {
                        "No new photos or videos from your camera in this trip's dates"
                    } else {
                        "Added $photos photo(s) and $videos video(s) from your camera"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
            val mediaPermissions = if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            val requestMediaPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                if (results.values.any { it }) runAutoAdd()
                else Toast.makeText(context, "Allow photo & video access to find your camera shots", Toast.LENGTH_LONG).show()
            }
            fun hasMediaAccess() = mediaPermissions.any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            val requestMediaForPicker = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                if (results.values.any { it }) {
                    navController.navigate("tripDetail/$tripId/pick")
                } else {
                    // No library access: the system photo picker still works without it.
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
            }

            TripDetailScreen(
                tripId = tripId,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onAddLeg = { navController.navigate("tripDetail/$tripId/addLeg") },
                onCapturePhoto = { withCamera { launchPhoto() } },
                onCaptureVideo = { withCamera { launchVideo() } },
                onPickMedia = {
                    if (hasMediaAccess()) navController.navigate("tripDetail/$tripId/pick")
                    else requestMediaForPicker.launch(mediaPermissions)
                },
                onAutoAddMedia = {
                    val hasAccess = mediaPermissions.any {
                        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                    }
                    if (hasAccess) runAutoAdd() else requestMediaPermissions.launch(mediaPermissions)
                },
                onOpenMedia = { index -> navController.navigate("tripDetail/$tripId/media/$index") },
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
            "tripDetail/{tripId}/pick",
            arguments = listOf(navArgument("tripId") { type = NavType.LongType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getLong("tripId") ?: return@composable
            MediaPickerScreen(tripId = tripId, viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable(
            "tripDetail/{tripId}/media/{index}",
            arguments = listOf(
                navArgument("tripId") { type = NavType.LongType },
                navArgument("index") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getLong("tripId") ?: return@composable
            val index = backStackEntry.arguments?.getInt("index") ?: 0
            MediaViewerScreen(tripId = tripId, startIndex = index, viewModel = viewModel, onBack = { navController.popBackStack() })
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
