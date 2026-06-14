package com.portalhacks.frame

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Photos setup/settings screen, in Jetpack Compose (migration Milestone 3).
 * Replaces the view-based status screen; reuses the existing prefs
 * ({@link ConfigReceiver}) and hands off to the Java {@link PhotosActivity} for
 * the camera scanner / manual entry (those flows are ported in a later milestone).
 */
class SettingsActivity : ComponentActivity() {

    private val prefs: SharedPreferences
        get() = getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)

    // Used to load the album's first photo for the preview thumbnail (reuses the
    // slideshow's on-disk image cache).
    private val loader by lazy { ImageLoader(this) }

    // Bumped on resume so the screen re-reads prefs after returning from the scanner /
    // manual entry (the album may have changed there).
    private val resumeTick = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
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
                SettingsScreen()
            }
        }
    }

    private fun openScreensaver() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (_: Exception) {
        }
    }

    private fun gotoPhotos(goto: String) {
        startActivity(Intent(this, PhotosActivity::class.java).putExtra("goto", goto))
    }

    private fun isOurScreensaver(): Boolean = try {
        val enabled = Settings.Secure.getInt(contentResolver, "screensaver_enabled", 0) == 1
        val comp = Settings.Secure.getString(contentResolver, "screensaver_components")
        enabled && comp != null && comp.contains(packageName)
    } catch (_: Exception) {
        false
    }

    // ----------------------------------------------------------------- UI

    @Composable
    private fun SettingsScreen() {
        val ctx = LocalContext.current
        var tick by remember { mutableIntStateOf(0) } // bump to recompose after pref writes
        resumeTick.intValue // read so returning from the scanner re-reads albums below
        tick // read so writes that bump it recompose
        val albums = Albums.list(prefs)
        val hasAlbum = albums.isNotEmpty()

        // Card groups, so the layout can be one or two columns by available width.
        val sourceCards: @Composable () -> Unit = {
            Card("Screensaver") {
                val active = isOurScreensaver()
                Body(
                    if (active)
                        "✓ Frame is your screensaver. Your photos appear when the Portal is idle."
                    else
                        "Tap below, then choose “Frame” so your photos show when the Portal is idle.",
                )
                Spacer(Modifier.height(12.dp))
                if (active) OutlineBtn("Change screensaver") { openScreensaver() }
                else PrimaryBtn("Use as screensaver") { openScreensaver() }
            }
            Card(if (hasAlbum) "Albums" else "No albums yet") {
                if (hasAlbum) {
                    // One removable row per album; the slideshow plays them all merged.
                    albums.forEachIndexed { i, url ->
                        if (i > 0) Divider()
                        AlbumRow(url) { Albums.remove(prefs, url); tick++ }
                    }
                    Spacer(Modifier.height(14.dp))
                } else {
                    Body("Add a Google Photos or iCloud shared album to show your own photos.")
                    Spacer(Modifier.height(12.dp))
                }
                // One add-album screen — scan a QR or paste a link there.
                PrimaryBtn("Add album") { gotoPhotos("scan") }
            }
        }
        val settingsCards: @Composable () -> Unit = {
            Card("Slideshow") {
                CycleRow("Seconds per photo", fmtDelay(getLong(ConfigReceiver.KEY_DELAY_MS, 6000))) {
                    val next = cycle(DELAY_CHOICES, getLong(ConfigReceiver.KEY_DELAY_MS, 6000), 0)
                    setLong(ConfigReceiver.KEY_DELAY_MS, next); tick++
                }
                Divider()
                ToggleRow("Shuffle photos", ConfigReceiver.KEY_SHUFFLE, false) { tick++ }
                Divider()
                CycleRow("Transition", fadeLabel(getLong(ConfigReceiver.KEY_FADE_MS, 1200))) {
                    val next = cycle(FADE_CHOICES, getLong(ConfigReceiver.KEY_FADE_MS, 1200), 1)
                    setLong(ConfigReceiver.KEY_FADE_MS, next); tick++
                }
                Divider()
                ToggleRow("Side-by-side portraits", ConfigReceiver.KEY_PAIRS, true) { tick++ }
                Divider()
                ToggleRow("Cinematic motion", ConfigReceiver.KEY_KEN_BURNS, true) { tick++ }
                Divider()
                ToggleRow("Photo captions", ConfigReceiver.KEY_CAPTIONS, true) { tick++ }
            }
            Card("Ambient intelligence") {
                ToggleRow("Face-aware framing", ConfigReceiver.KEY_FACE, true) { tick++ }
                Divider()
                ToggleRow("Auto-enhance photos", ConfigReceiver.KEY_ENHANCE, true) { tick++ }
                Divider()
                ToggleRow("Ambient color glow", ConfigReceiver.KEY_AMBIENT, true) { tick++ }
                Divider()
                ToggleRow("Clock & weather", ConfigReceiver.KEY_CLOCK, true) { tick++ }
                Divider()
                ToggleRow("Night warmth", ConfigReceiver.KEY_NIGHT, true) { tick++ }
                Divider()
                ToggleRow("On This Day memories", ConfigReceiver.KEY_ON_THIS_DAY, true) { tick++ }
            }
        }

        BoxWithConstraints(
            Modifier.fillMaxSize().background(PortalColors.Bg),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Two columns only when there's room (Portal Go/+ landscape); one column
            // on the original Portal's portrait screen and the small Portal Mini.
            val twoCol = maxWidth >= 880.dp
            val sidePad = if (maxWidth < 560.dp) 24.dp else 40.dp
            Column(
                Modifier.widthIn(max = if (twoCol) 1100.dp else 620.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = sidePad, vertical = 72.dp),
            ) {
                Text(
                    if (hasAlbum) "Your photos" else "Show your photos",
                    color = PortalColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))

                if (twoCol) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) { sourceCards() }
                        Spacer(Modifier.width(24.dp))
                        Column(Modifier.weight(1f)) { settingsCards() }
                    }
                } else {
                    sourceCards()
                    settingsCards()
                }

                // Leave room so the last card scrolls clear of the pinned Done bar below.
                Spacer(Modifier.height(96.dp))
            }

            // Pinned Done bar — always visible, so it's discoverable without scrolling
            // to the bottom of a long settings list.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(PortalColors.Bg),
            ) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(PortalColors.Hairline))
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Button(
                        onClick = { finish() },
                        modifier = Modifier.widthIn(max = 1100.dp).fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PortalColors.Surface),
                    ) { Text("Done", color = PortalColors.Text, fontSize = 18.sp) }
                }
            }
        }
    }

    @Composable
    private fun Card(title: String, content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxWidth().padding(top = 16.dp)
                .clip(RoundedCornerShape(20.dp)).background(PortalColors.Surface)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text(
                title.uppercase(), color = PortalColors.TextMuted, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }

    /**
     * One album in the list: a thumbnail of its first photo, the album title +
     * provider/link, and a (two-tap) Remove button. The preview starts from the cache
     * the slideshow writes; if the album was just added and isn't cached yet, it's
     * fetched once in the background, persisted, then shown.
     */
    @Composable
    private fun AlbumRow(url: String, onRemove: () -> Unit) {
        var bmp by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
        var title by remember(url) { mutableStateOf("") }
        var failed by remember(url) { mutableStateOf(false) }
        var armed by remember(url) { mutableStateOf(false) }

        // Show the photo, or fall back to the error state (don't hang on "Loading…").
        val onBitmap = ImageLoader.Callback { b -> if (b != null) bmp = b else failed = true }

        LaunchedEffect(url) {
            AlbumCache.title(prefs, url)?.let { title = it }
            val firstId = AlbumCache.firstId(prefs, url)
            if (firstId != null) {
                loader.load(firstId, PREVIEW_W, PREVIEW_H, onBitmap)
            } else {
                // Not fetched yet (just added) — fetch once, persist, then show.
                loader.executor().execute {
                    try {
                        val a = PhotoSources.fetch(url)
                        if (a.slides.isEmpty()) {
                            runOnUiThread { failed = true }
                            return@execute
                        }
                        AlbumCache.write(prefs, url, a.slides, a.title)
                        val id = a.slides[0].id
                        runOnUiThread {
                            if (Albums.list(prefs).contains(url)) {
                                title = a.title ?: ""
                                loader.load(id, PREVIEW_W, PREVIEW_H, onBitmap)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("PortalFrame", "album preview fetch failed: $url", e)
                        runOnUiThread { failed = true }
                    }
                }
            }
        }

        var on by remember(url) { mutableStateOf(Albums.isEnabled(prefs, url)) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shape = RoundedCornerShape(10.dp)
            val image = bmp
            Box(
                Modifier.width(120.dp).aspectRatio(16f / 9f).clip(shape).background(PortalColors.Field),
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = "First photo from the album",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        if (failed) "—" else "…",
                        color = PortalColors.TextMuted, fontSize = 16.sp,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title.ifEmpty { if (failed) "Couldn't load" else "Loading…" },
                    color = if (on) PortalColors.Text else PortalColors.TextMuted,
                    fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    providerLabel(url) + " · " + url,
                    color = PortalColors.TextMuted, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                )
                Spacer(Modifier.height(8.dp))
                // Stop/resume (keeps the album, just pauses it) + Remove (two-tap).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = on,
                        onCheckedChange = { on = it; Albums.setEnabled(prefs, url, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = PortalColors.Blue),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (on) "Playing" else "Stopped",
                        color = PortalColors.TextMuted, fontSize = 14.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (armed) "Confirm" else "Remove",
                        color = PortalColors.Red, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { if (!armed) armed = true else onRemove() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    /** "Google Photos" / "iCloud" / "Link" for the album's URL. */
    private fun providerLabel(url: String): String =
        PhotoSources.providerFor(url)?.displayName ?: "Link"

    @Composable
    private fun Body(text: String) =
        Text(text, color = PortalColors.TextBody, fontSize = 17.sp)

    @Composable
    private fun Divider() = Box(
        Modifier.fillMaxWidth().height(1.dp).padding(vertical = 0.dp).background(PortalColors.Hairline),
    )

    @Composable
    private fun PrimaryBtn(label: String, onClick: () -> Unit) = Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PortalColors.Blue),
    ) { Text(label, color = PortalColors.OnPrimary, fontSize = 18.sp) }

    @Composable
    private fun OutlineBtn(label: String, onClick: () -> Unit) = OutlinedButton(
        onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text(label, color = PortalColors.Text, fontSize = 18.sp) }

    @Composable
    private fun ToggleRow(label: String, key: String, def: Boolean, onChanged: () -> Unit) {
        var on by remember(key) { mutableStateOf(prefs.getBoolean(key, def)) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = PortalColors.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = on,
                onCheckedChange = {
                    on = it
                    prefs.edit().putBoolean(key, it).apply()
                    onChanged()
                },
                colors = SwitchDefaults.colors(checkedTrackColor = PortalColors.Blue),
            )
        }
    }

    @Composable
    private fun CycleRow(label: String, value: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = PortalColors.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text("$value  ›", color = PortalColors.Blue, fontSize = 18.sp)
        }
    }

    // ----------------------------------------------------------------- prefs/helpers

    private fun getLong(key: String, def: Long) = prefs.getLong(key, def)
    private fun setLong(key: String, v: Long) = prefs.edit().putLong(key, v).apply()

    companion object {
        private const val PREVIEW_W = 720 // 16:9 thumbnail decode size for the album preview
        private const val PREVIEW_H = 405

        private val DELAY_CHOICES = longArrayOf(4000, 6000, 10000, 30000, 60000)
        private val FADE_CHOICES = longArrayOf(2000, 1200, 500)
        private val FADE_LABELS = arrayOf("Slow", "Normal", "Fast")

        private fun cycle(choices: LongArray, cur: Long, fallback: Int): Long {
            for (i in choices.indices) if (choices[i] == cur) return choices[(i + 1) % choices.size]
            return choices[fallback]
        }

        private fun fadeLabel(ms: Long): String {
            for (i in FADE_CHOICES.indices) if (FADE_CHOICES[i] == ms) return FADE_LABELS[i]
            return "Normal"
        }

        private fun fmtDelay(ms: Long): String {
            val s = ms / 1000
            return if (s >= 60) "${s / 60}m" else "${s}s"
        }
    }
}
