package com.portalhacks.frame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The Photos setup/settings screen, in Jetpack Compose. Reads/writes the shared
 * prefs ([ConfigReceiver]) and hands off to [PhotosActivity] for the camera
 * scanner / manual link entry.
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

    // Bumped when a photo arrives over the LAN while this screen is open, so the
    // "Photos added from phones" list refreshes immediately (DropServerService broadcasts).
    private val uploadTick = mutableIntStateOf(0)
    private var uploadReceiverRegistered = false
    private val uploadReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            uploadTick.intValue++
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        // Re-assert Frame (and restart the guard if it was killed) when opted in.
        ScreensaverGuardService.startIfEnabled(this)
        if (!uploadReceiverRegistered) {
            ContextCompat.registerReceiver(
                this, uploadReceiver, IntentFilter(DropServerService.ACTION_UPLOAD),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            uploadReceiverRegistered = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (uploadReceiverRegistered) {
            unregisterReceiver(uploadReceiver)
            uploadReceiverRegistered = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdown() // don't leak the decode thread pool each time Settings closes
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ensure the LAN drop server is running so the QR below is live and scannable.
        DropServerService.start(this)
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

    /**
     * Make Frame the screensaver. With WRITE_SECURE_SETTINGS we set it directly and
     * start the guard so the launcher can't reclaim the slot on rotation; otherwise we
     * fall back to the system Screen-saver picker.
     */
    private fun enableScreensaver() {
        prefs.edit().putBoolean(ConfigReceiver.KEY_GUARD, true).apply()
        if (Screensaver.claim(this)) {
            ScreensaverGuardService.start(this)
        } else {
            openScreensaver()
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
        // Only one tuning group is expanded at a time, so the "Customize" pane can't balloon.
        var openGroup by remember { mutableStateOf<String?>(null) }
        val albums = Albums.list(prefs)
        val hasAlbum = albums.isNotEmpty()

        // Card groups, so the layout can be one or two columns by available width.
        val sourceCards: @Composable () -> Unit = {
            Card("Screensaver") {
                val active = isOurScreensaver()
                val protectedMode = Screensaver.canWrite(ctx)
                Body(
                    when {
                        active && protectedMode ->
                            "✓ Frame is your screensaver — and Frame keeps it that way, even after a rotation."
                        active ->
                            "✓ Frame is your screensaver. Your photos appear when the Portal is idle."
                        else ->
                            "Tap below so your photos show when the Portal is idle."
                    },
                )
                Spacer(Modifier.height(12.dp))
                if (active) PrimaryBtn("Change screensaver") { openScreensaver() }
                else PrimaryBtn("Use as screensaver") { enableScreensaver(); tick++ }
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
            DropCard()
            UploadsCard()
        }
        // The tuning options live in one bounded "Customize" panel as collapsible rows, so the
        // pane is a short list of headers (Playback / Look & motion / Clock & night / Extras)
        // instead of a wall of ~16 toggles; tap a header to open just that group (one at a time).
        val settingsCards: @Composable () -> Unit = {
            Column(
                Modifier.fillMaxWidth().padding(top = 12.dp)
                    .clip(RoundedCornerShape(20.dp)).background(PortalColors.Surface)
                    .padding(horizontal = 24.dp, vertical = 6.dp),
            ) {
                CollapsibleRow(
                    "Playback", "Time, order, transitions, captions",
                    openGroup == "Playback", { openGroup = if (openGroup == "Playback") null else "Playback" },
                ) {
                    DurationSliderRow { tick++ }
                    Divider()
                    ToggleRow("Shuffle photos", ConfigReceiver.KEY_SHUFFLE, false) { tick++ }
                    Divider()
                    CycleRow("Transition", fadeLabel(getLong(ConfigReceiver.KEY_FADE_MS, 1200))) {
                        val next = cycle(FADE_CHOICES, getLong(ConfigReceiver.KEY_FADE_MS, 1200), 1)
                        setLong(ConfigReceiver.KEY_FADE_MS, next); tick++
                    }
                    Divider()
                    ToggleRow("Pair photos to fill the screen", ConfigReceiver.KEY_PAIRS, false) { tick++ }
                    Divider()
                    ToggleRow(
                        "Zoom single photos to fill",
                        ConfigReceiver.KEY_ZOOM_FILL,
                        false,
                        subtitle = "Crop a single photo to fill the screen. Off: show the whole photo " +
                            "over a blurred fill. Paired photos always fill.",
                    ) { tick++ }
                    Divider()
                    ToggleRow("Photo captions", ConfigReceiver.KEY_CAPTIONS, true) { tick++ }
                }
                Divider()
                CollapsibleRow(
                    "Look & motion", "Motion, color, enhance, framing",
                    openGroup == "Look & motion", { openGroup = if (openGroup == "Look & motion") null else "Look & motion" },
                ) {
                    ToggleRow("Cinematic motion", ConfigReceiver.KEY_KEN_BURNS, true) { tick++ }
                    Divider()
                    ToggleRow("Face-aware framing", ConfigReceiver.KEY_FACE, true) { tick++ }
                    Divider()
                    ToggleRow("Auto-enhance photos", ConfigReceiver.KEY_ENHANCE, ConfigReceiver.DEFAULT_ENHANCE) { tick++ }
                    Divider()
                    ToggleRow("Ambient color glow", ConfigReceiver.KEY_AMBIENT, true) { tick++ }
                }
                Divider()
                CollapsibleRow(
                    "Clock & night", "Clock, low light, night warmth",
                    openGroup == "Clock & night", { openGroup = if (openGroup == "Clock & night") null else "Clock & night" },
                ) {
                    ToggleRow(
                        "Clock & weather", ConfigReceiver.KEY_CLOCK, true,
                        subtitle = "Long-press the clock on the screensaver to move or resize it.",
                    ) { tick++ }
                    Divider()
                    CycleRow("Clock position & size", "Reset") {
                        prefs.edit()
                            .putFloat(ConfigReceiver.KEY_CLOCK_DX, ConfigReceiver.DEFAULT_CLOCK_DX)
                            .putFloat(ConfigReceiver.KEY_CLOCK_DY, ConfigReceiver.DEFAULT_CLOCK_DY)
                            .putFloat(ConfigReceiver.KEY_CLOCK_SCALE, ConfigReceiver.DEFAULT_CLOCK_SCALE)
                            .apply()
                        tick++
                    }
                    Divider()
                    ToggleRow("Only clock in low light", ConfigReceiver.KEY_CLOCK_LOW_LIGHT, ConfigReceiver.DEFAULT_CLOCK_LOW_LIGHT) { tick++ }
                    Divider()
                    ToggleRow("Night warmth", ConfigReceiver.KEY_NIGHT, true) { tick++ }
                }
                Divider()
                CollapsibleRow(
                    "Extras", "Memories, sticky notes",
                    openGroup == "Extras", { openGroup = if (openGroup == "Extras") null else "Extras" },
                ) {
                    ToggleRow("On This Day memories", ConfigReceiver.KEY_ON_THIS_DAY, true) { tick++ }
                    Divider()
                    ToggleRow(
                        "Sticky notes & fortunes", ConfigReceiver.KEY_NOTES, ConfigReceiver.DEFAULT_NOTES,
                        subtitle = "Show a post-it note on the frame, or a fetched wisdom line when no note is set.",
                    ) { tick++ }
                }
            }
        }

        BoxWithConstraints(
            Modifier.fillMaxSize().background(PortalColors.Bg),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Two columns only when there's real room (Portal Go/+ landscape); one column on
            // the original Portal's portrait screen and the small Portal Mini. The columns
            // aren't equal weight: setup (QR, album rows, thumbnails) is denser than the
            // collapsed tuning pane, so it gets the wider ~60% share.
            val twoCol = maxWidth >= 1000.dp
            val sidePad = if (maxWidth < 560.dp) 24.dp else 40.dp
            Column(
                Modifier.widthIn(max = if (twoCol) 1160.dp else 620.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = sidePad, vertical = 72.dp),
            ) {
                Text(
                    if (hasAlbum) "Your photos" else "Show your photos",
                    color = PortalColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                )

                if (twoCol) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(3f)) {
                            ColumnHeader("Set up")
                            sourceCards()
                        }
                        Spacer(Modifier.width(24.dp))
                        Column(Modifier.weight(2f)) {
                            ColumnHeader("Customize")
                            settingsCards()
                        }
                    }
                } else {
                    ColumnHeader("Set up")
                    sourceCards()
                    ColumnHeader("Customize")
                    settingsCards()
                }

                // Trailing breathing room at the end of the scroll (no pinned bar:
                // leaving the screen is the Portal system top bar's back button).
                Spacer(Modifier.height(24.dp))
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

    /** A heading above a layout column, e.g. "Set up" / "Customize", sized for tabletop distance. */
    @Composable
    private fun ColumnHeader(text: String) {
        Text(
            text, color = PortalColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp, bottom = 2.dp),
        )
    }

    /**
     * A collapsible tuning section rendered as a row inside the "Customize" panel: a 64dp header
     * (title + one-line summary + chevron) that expands on tap to reveal its controls. Expansion
     * is controlled by the caller so only one group is open at a time.
     */
    @Composable
    private fun CollapsibleRow(
        title: String,
        summary: String,
        expanded: Boolean,
        onToggle: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .clickable(onClickLabel = if (expanded) "Collapse $title" else "Expand $title") { onToggle() }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = PortalColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    if (!expanded) {
                        Text(summary, color = PortalColors.TextMuted, fontSize = 15.sp)
                    }
                }
                Text(if (expanded) "▾" else "▸", color = PortalColors.TextMuted, fontSize = 22.sp)
            }
            if (expanded) {
                Column(Modifier.padding(bottom = 12.dp)) { content() }
            }
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
            val zoomFill = prefs.getBoolean(ConfigReceiver.KEY_ZOOM_FILL, ConfigReceiver.DEFAULT_ZOOM_FILL)
            if (firstId != null) {
                loader.load(firstId, PREVIEW_W, PREVIEW_H, zoomFill, onBitmap)
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
                                title = a.title
                                loader.load(id, PREVIEW_W, PREVIEW_H, zoomFill, onBitmap)
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
                    color = PortalColors.TextMuted, fontSize = 14.sp,
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

    /**
     * "Add photos from your phone": the QR + URL anyone on the Wi-Fi scans to push photos
     * straight onto the frame (no app, no account). The QR carries the access token, so a
     * device that never scanned it can't post.
     */
    @Composable
    private fun DropCard() {
        val ctx = LocalContext.current
        var url by remember { mutableStateOf<String?>(null) }
        var qr by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        // Re-key to resumeTick so the URL/QR re-resolves when returning to the screen (e.g. after
        // Wi-Fi connected) rather than being stuck on "Starting…" from a one-shot poll.
        LaunchedEffect(resumeTick.intValue) {
            DropServerService.start(ctx)
            // Wait for the server to bind, then build the tokenized URL + QR.
            var u = DropServerStatus.url(ctx)
            var tries = 0
            while (u == null && tries < 24) {
                delay(250)
                u = DropServerStatus.url(ctx)
                tries++
            }
            val ready = u
            url = ready
            if (ready != null) qr = withContext(Dispatchers.Default) { QrCodes.bitmap(ready, 480) }
        }
        Card("Add photos from a phone") {
            Body(
                "On a phone connected to this Wi-Fi, scan the QR code and choose photos. " +
                    "They'll appear on Frame right away — no app or account needed.",
            )
            Spacer(Modifier.height(16.dp))
            val image = qr
            if (image != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp),
                    ) {
                        Image(
                            bitmap = image.asImageBitmap(),
                            contentDescription = "QR code to add photos from a phone",
                            modifier = Modifier.width(248.dp).aspectRatio(1f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // The raw link is a long, opaque-token fallback — tuck it behind a tap.
                var showLink by remember { mutableStateOf(false) }
                Text(
                    if (showLink) "Hide link" else "Can't scan? Show link",
                    color = PortalColors.Blue, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showLink = !showLink }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                )
                if (showLink) {
                    Text(
                        url ?: "",
                        color = PortalColors.TextMuted, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                    )
                }
            } else {
                Body(
                    if (DropServerStatus.lanIp() == null) {
                        "Connect the frame to Wi-Fi to let phones add photos."
                    } else {
                        "Starting…"
                    },
                )
            }
        }
    }

    /**
     * A compact entry point: a peek at recent phone-pushed photos + a button into the
     * full-screen grid manager ([UploadsActivity]), which scales to many photos.
     */
    @Composable
    private fun UploadsCard() {
        val ctx = LocalContext.current
        val rTick = resumeTick.intValue // refresh after returning to the screen
        val uTick = uploadTick.intValue // refresh immediately when a photo arrives over the LAN
        val files = remember(rTick, uTick) { LocalUploads.files(ctx) }
        if (files.isEmpty()) return
        Card("Photos added from phones (${files.size})") {
            // Just a peek at the most recent — full viewing/management lives in a grid that
            // scales to hundreds of photos, one tap away.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(files.take(15), key = { it.path }) { f -> PreviewThumb(f) }
            }
            Spacer(Modifier.height(14.dp))
            PrimaryBtn("Manage photos") {
                startActivity(Intent(this@SettingsActivity, UploadsActivity::class.java))
            }
        }
    }

    /** A non-interactive thumbnail for the Settings preview strip. */
    @Composable
    private fun PreviewThumb(file: File) {
        var bmp by remember(file.path) { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(file.path) {
            loader.load(file.absolutePath, 200, 200, true, ImageLoader.Callback { b -> bmp = b })
        }
        Box(Modifier.size(120.dp).clip(RoundedCornerShape(10.dp)).background(PortalColors.Field)) {
            val image = bmp
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "Photo added from a phone",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }

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
    private fun ToggleRow(
        label: String,
        key: String,
        def: Boolean,
        subtitle: String? = null,
        onChanged: () -> Unit,
    ) {
        var on by remember(key) { mutableStateOf(prefs.getBoolean(key, def)) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = PortalColors.Text, fontSize = 18.sp)
                if (subtitle != null) {
                    Text(subtitle, color = PortalColors.Text.copy(alpha = 0.6f), fontSize = 14.sp)
                }
            }
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

    /**
     * "Time per photo": a slider that snaps across [DELAY_PRESETS] (4 sec … 1 day). The slider value
     * is the preset INDEX (a plain linear ms slider can't span that range). The label updates live
     * while dragging; the pref is committed on release so we don't thrash prefs every tick.
     */
    @Composable
    private fun DurationSliderRow(onChanged: () -> Unit) {
        var idx by remember {
            mutableIntStateOf(nearestPresetIndex(getLong(ConfigReceiver.KEY_DELAY_MS, ConfigReceiver.DEFAULT_DELAY_MS)))
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Time per photo", color = PortalColors.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Text(
                    fmtDelay(DELAY_PRESETS[idx]),
                    color = PortalColors.Blue, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                )
            }
            Slider(
                value = idx.toFloat(),
                onValueChange = { idx = it.roundToInt() },
                valueRange = 0f..(DELAY_PRESETS.size - 1).toFloat(),
                steps = DELAY_PRESETS.size - 2,
                onValueChangeFinished = {
                    setLong(ConfigReceiver.KEY_DELAY_MS, DELAY_PRESETS[idx]); onChanged()
                },
                colors = SliderDefaults.colors(
                    thumbColor = PortalColors.Blue,
                    activeTrackColor = PortalColors.Blue,
                    inactiveTrackColor = PortalColors.Text.copy(alpha = 0.18f),
                    // Hide the per-step tick dots — 13 of them clutter the track.
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
            // Muted range endpoints so the span (seconds → a day) is legible at a glance.
            Row(Modifier.fillMaxWidth()) {
                Text(
                    fmtDelay(DELAY_PRESETS.first()),
                    color = PortalColors.TextMuted, fontSize = 14.sp, modifier = Modifier.weight(1f),
                )
                Text(fmtDelay(DELAY_PRESETS.last()), color = PortalColors.TextMuted, fontSize = 14.sp)
            }
        }
    }

    // ----------------------------------------------------------------- prefs/helpers

    private fun getLong(key: String, def: Long) = prefs.getLong(key, def)
    private fun setLong(key: String, v: Long) = prefs.edit().putLong(key, v).apply()

    companion object {
        private const val PREVIEW_W = 720 // 16:9 thumbnail decode size for the album preview
        private const val PREVIEW_H = 405

        // "Time per photo" presets the slider snaps across (4 sec … 1 day, ms). Keeps the old
        // values (4s/6s/10s/30s/60s) so previously-saved delays still land on a stop.
        private val DELAY_PRESETS = longArrayOf(
            4_000, 6_000, 10_000, 30_000, // seconds
            60_000, 300_000, 600_000, 1_800_000, // 1m, 5m, 10m, 30m
            3_600_000, 10_800_000, 21_600_000, 43_200_000, // 1h, 3h, 6h, 12h
            86_400_000, // 1 day
        )
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

        /** Index of the preset closest to [ms] (so a legacy/odd saved value still maps to a stop). */
        private fun nearestPresetIndex(ms: Long): Int {
            var best = 0
            for (i in DELAY_PRESETS.indices) {
                if (Math.abs(DELAY_PRESETS[i] - ms) < Math.abs(DELAY_PRESETS[best] - ms)) best = i
            }
            return best
        }

        private fun fmtDelay(ms: Long): String = when {
            ms >= 86_400_000 -> plural(ms / 86_400_000, "day")
            ms >= 3_600_000 -> plural(ms / 3_600_000, "hour")
            ms >= 60_000 -> "${ms / 60_000} min"
            else -> "${ms / 1000} sec"
        }

        private fun plural(n: Long, unit: String): String = "$n $unit" + if (n == 1L) "" else "s"
    }
}
