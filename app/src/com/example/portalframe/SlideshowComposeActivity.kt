package com.example.portalframe

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Compose port of the slideshow core (migration Milestone 4): crossfade + cinematic
 * Ken Burns + a clock overlay, reusing the Java {@link ImageLoader} (which already
 * screen-fills each bitmap). Developed as a separate activity so the verified Java
 * screensaver path stays intact; once it reaches feature parity it becomes the dream
 * target. Currently drives the bundled sample slides.
 */
class SlideshowComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
        setContent { Slideshow(onTap = { finish() }) }
    }
}

private const val HOLD_MS = 6000
private const val FADE_MS = 1200

private data class Kb(
    val s0: Float, val s1: Float,
    val tx0: Float, val tx1: Float, val ty0: Float, val ty1: Float,
) {
    fun scale(f: Float) = s0 + (s1 - s0) * f
    fun tx(f: Float) = tx0 + (tx1 - tx0) * f
    fun ty(f: Float) = ty0 + (ty1 - ty0) * f

    companion object {
        fun random(w: Int, h: Int): Kb {
            fun z() = 1.08f + Math.random().toFloat() * 0.10f
            val s0 = z(); val s1 = z()
            val minS = minOf(s0, s1)
            val sx = (minS - 1f) / 2f * w * 0.9f
            val sy = (minS - 1f) / 2f * h * 0.9f
            fun r(s: Float) = ((Math.random().toFloat() * 2f) - 1f) * s
            return Kb(s0, s1, r(sx), r(sx), r(sy), r(sy))
        }
    }
}

private suspend fun ImageLoader.loadBitmap(id: String, w: Int, h: Int): Bitmap? =
    suspendCancellableCoroutine { cont ->
        load(id, w, h) { bmp -> if (cont.isActive) cont.resume(bmp) }
    }

@Composable
private fun Slideshow(onTap: () -> Unit) {
    val context = LocalContext.current
    val dm = context.resources.displayMetrics
    val w = if (dm.widthPixels > 0) dm.widthPixels else 1280
    val h = if (dm.heightPixels > 0) dm.heightPixels else 800
    val loader = remember { ImageLoader(context) }
    val items = remember { assetSlides(context) }

    var back by remember { mutableStateOf<Bitmap?>(null) }
    var front by remember { mutableStateOf<Bitmap?>(null) }
    val frontAlpha = remember { Animatable(0f) }
    val kbFrac = remember { Animatable(0f) }
    var kb by remember { mutableStateOf(Kb.random(w, h)) }

    var clock by remember { mutableStateOf(currentTime(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = currentTime(context)
            delay(1000L * (60 - (System.currentTimeMillis() / 1000 % 60)))
        }
    }

    LaunchedEffect(items) {
        if (items.isEmpty()) return@LaunchedEffect
        back = loader.loadBitmap(items[0], w, h)
        kb = Kb.random(w, h)
        var kbJob = launch { kbFrac.snapTo(0f); kbFrac.animateTo(1f, tween(HOLD_MS + FADE_MS, easing = LinearEasing)) }
        var i = 0
        while (true) {
            delay(HOLD_MS.toLong())
            val next = (i + 1) % items.size
            val bmp = loader.loadBitmap(items[next], w, h)
            if (bmp == null) { i = next; continue }
            front = bmp
            frontAlpha.snapTo(0f)
            val nextKb = Kb.random(w, h)
            frontAlpha.animateTo(1f, tween(FADE_MS, easing = LinearEasing)) // crossfade
            back = bmp
            front = null
            frontAlpha.snapTo(0f)
            kb = nextKb
            kbJob.cancel()
            kbJob = launch { kbFrac.snapTo(0f); kbFrac.animateTo(1f, tween(HOLD_MS + FADE_MS, easing = LinearEasing)) }
            i = next
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onTap() },
        contentAlignment = Alignment.BottomStart,
    ) {
        back?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val f = kbFrac.value
                    scaleX = kb.scale(f); scaleY = kb.scale(f)
                    translationX = kb.tx(f); translationY = kb.ty(f)
                },
            )
        }
        front?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = frontAlpha.value },
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.7f to Color.Transparent, 1f to Color(0xB3000000)),
            ),
        )
        Text(
            clock, color = Color.White, fontSize = 80.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 28.dp, bottom = 95.dp),
        )
    }
}

private fun assetSlides(context: Context): List<String> = try {
    (context.assets.list("slides") ?: emptyArray())
        .filter {
            val l = it.lowercase()
            l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".webp")
        }
        .sorted()
        .map { "slides/$it" }
} catch (_: Exception) {
    emptyList()
}

private fun currentTime(context: Context): String {
    val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date())
}
