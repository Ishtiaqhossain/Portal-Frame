package com.portalhacks.frame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Full-screen manager for photos pushed from phones. A [LazyVerticalGrid] scrolls vertically
 * and only decodes visible tiles, so it stays smooth whether there are five photos or the full
 * cap of hundreds — unlike a single row, you can actually see and manage them. Live-refreshes
 * when a new photo arrives (the [DropServerService] broadcast). Reached from Settings.
 */
class UploadsActivity : ComponentActivity() {

    private val loader by lazy { ImageLoader(this) }
    private val tick = mutableIntStateOf(0) // bumped to re-read the folder (resume / new upload)
    private var receiverRegistered = false
    private val uploadReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            tick.intValue++
        }
    }

    override fun onResume() {
        super.onResume()
        tick.intValue++
        if (!receiverRegistered) {
            registerReceiver(uploadReceiver, IntentFilter(DropServerService.ACTION_UPLOAD))
            receiverRegistered = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (receiverRegistered) {
            unregisterReceiver(uploadReceiver)
            receiverRegistered = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdown() // don't leak the decode thread pool each time this screen closes
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = PortalColors.Blue,
                    onPrimary = PortalColors.OnPrimary,
                    background = PortalColors.Bg,
                    surface = PortalColors.Surface,
                ),
                typography = rememberInterTypography(this),
            ) {
                UploadsScreen()
            }
        }
    }

    @Composable
    private fun UploadsScreen() {
        val ctx = LocalContext.current
        val t = tick.intValue
        var files by remember(t) { mutableStateOf(LocalUploads.files(ctx)) }
        var confirmClear by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxSize().background(PortalColors.Bg)
                .padding(start = 24.dp, end = 24.dp, top = 72.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Photos from phones",
                        color = PortalColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (files.isEmpty()) "None yet" else "${files.size} added",
                        color = PortalColors.TextMuted, fontSize = 16.sp,
                    )
                }
                if (files.isNotEmpty()) {
                    Box(
                        Modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
                            .clickable(onClickLabel = "Remove all photos added from phones") {
                                confirmClear = true
                            }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Remove all", color = PortalColors.Red, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No photos yet.\nScan the QR in Frame Settings to add some from a phone.",
                        color = PortalColors.TextMuted, fontSize = 18.sp, textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(files, key = { it.path }) { f ->
                        Thumb(f) {
                            LocalUploads.delete(f)
                            files = LocalUploads.files(ctx)
                        }
                    }
                }
            }
        }
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Remove all ${files.size} photos?", fontSize = 20.sp) },
                text = {
                    Text(
                        "This removes every photo added from a phone. Your albums aren't affected.",
                        fontSize = 16.sp,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { LocalUploads.clearAll(ctx); files = emptyList(); confirmClear = false },
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) { Text("Remove all", color = PortalColors.Red, fontSize = 17.sp) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { confirmClear = false },
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) { Text("Cancel", fontSize = 17.sp) }
                },
            )
        }
    }

    /** One grid cell: a square thumbnail with a 64dp, labelled delete target. */
    @Composable
    private fun Thumb(file: File, onDelete: () -> Unit) {
        var bmp by remember(file.path) { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(file.path) {
            loader.load(file.absolutePath, 300, 300, true, ImageLoader.Callback { b -> bmp = b })
        }
        Box(
            Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(PortalColors.Field),
        ) {
            val image = bmp
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "Photo added from a phone",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                Modifier.align(Alignment.TopEnd).size(64.dp)
                    .clickable(onClickLabel = "Remove this photo") { onDelete() },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.padding(8.dp).size(38.dp).clip(CircleShape).background(Color(0xCC000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
