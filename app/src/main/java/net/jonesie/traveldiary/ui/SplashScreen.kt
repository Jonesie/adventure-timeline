package net.jonesie.traveldiary.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import net.jonesie.traveldiary.R

private const val SPLASH_MILLIS = 2000L

/**
 * Full-screen artwork (the app icon, centred on a dark backdrop) shown over the app on a cold start. The app is already composed underneath, so
 * its data and map are loading while this is up. Tap or back skips it; system bars stay hidden for
 * its duration and return as it fades.
 */
@Composable
fun SplashOverlay(visible: Boolean, onFinished: () -> Unit) {
    ImmersiveWhile(visible)
    AnimatedVisibility(visible = visible, exit = fadeOut(tween(350))) {
        SplashScreen(onFinished)
    }
}

@Composable
private fun ImmersiveWhile(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (active) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun SplashScreen(onFinished: () -> Unit) {
    BackHandler(onBack = onFinished)
    LaunchedEffect(Unit) {
        delay(SPLASH_MILLIS)
        onFinished()
    }

    val fadeIn = remember { Animatable(0f) }
    val settle = remember { Animatable(0.94f) }
    LaunchedEffect(Unit) { fadeIn.animateTo(1f, tween(450)) }
    LaunchedEffect(Unit) { settle.animateTo(1f, tween(SPLASH_MILLIS.toInt() + 400, easing = LinearOutSlowInEasing)) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0F2036), Color(0xFF16110B))))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onFinished),
        contentAlignment = Alignment.Center
    ) {
        // The rounded-square artwork sits centred: 80% of the width in portrait, 80% of the height in landscape.
        val portrait = maxWidth < maxHeight
        Image(
            painter = painterResource(R.drawable.splash_icon),
            contentDescription = "Adventure Timeline",
            contentScale = ContentScale.Fit,
            modifier = (if (portrait) Modifier.fillMaxWidth(0.8f) else Modifier.fillMaxHeight(0.8f))
                .aspectRatio(1f)
                .graphicsLayer {
                    alpha = fadeIn.value
                    scaleX = settle.value
                    scaleY = settle.value
                }
        )
    }
}
