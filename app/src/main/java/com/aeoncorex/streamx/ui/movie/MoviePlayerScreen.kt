package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.Keep
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI

// ═══════════════════════════════════════════════════════════════════
//  AeonCoreX brand tokens — player UI
// ═══════════════════════════════════════════════════════════════════
//
// Derived from the AeonCoreX logo (blue → violet gradient "A" mark) and
// brand palette (deep navy → sky-blue scale). Used throughout the player
// UI — progress bar, gesture overlays, controls, settings sheet — so the
// whole playback experience reads as one designed surface instead of
// default Material colors sprinkled with ad-hoc Color.Cyan/Color.Green.
private object AeonPlayer {
    // Brand scale (darkest → lightest), from the palette reference.
    val Navy900  = Color(0xFF001D39) // deepest background / scrims
    val Navy700  = Color(0xFF0A4174) // panel fills
    val Slate500 = Color(0xFF49769F) // secondary text / inactive icons
    val Teal500  = Color(0xFF4E8EA2) // mid accents
    val Teal300  = Color(0xFF6EA2B3) // soft accents
    val Sky300   = Color(0xFF7BBDE8) // primary interactive accent (replaces Color.Cyan)
    val Ice100   = Color(0xFFBDD8E9) // high-contrast on-dark text accent

    // Logo gradient — blue → violet. This is the ONE signature gradient
    // used sparingly (progress fills, active-state glows, the loading
    // ring) so it reads as intentional brand identity, not decoration.
    val BrandGradient = Brush.linearGradient(listOf(Color(0xFF2979FF), Color(0xFF8C4DFF)))
    val BrandSweep     = listOf(Color(0xFF2979FF), Sky300, Color(0xFF8C4DFF), Sky300, Color(0xFF2979FF))

    // Functional colors kept close to Material norms for recognizability
    // (amber=caution, green=good/live, red=error) but tuned to sit
    // comfortably next to the brand scale above rather than clashing.
    val Amber = Color(0xFFFFA726)
    val Green = Color(0xFF66BB6A)
    val Red   = Color(0xFFEF5350)

    // Glass panel surface — used for the settings sheet, gesture
    // overlays, and loading panel so they all share one "material."
    val GlassFill = Brush.verticalGradient(
        listOf(Navy900.copy(alpha = 0.88f), Color(0xFF060A12).copy(alpha = 0.94f))
    )
    val GlassBorder = Brush.linearGradient(
        listOf(Sky300.copy(0.35f), Color.White.copy(0.06f), Color(0xFF8C4DFF).copy(0.25f))
    )

    // Type scale — four steps used consistently across the settings sheet
    // and sub-pages, instead of ad-hoc sizes (10/11/12/13/14/15/16sp) picked
    // per-label. New text in the player UI should pick from these four.
    val TextCaption = 11.sp   // section labels, hints, diagnostics values
    val TextBody    = 13.sp   // secondary rows, subtitles under a title
    val TextTitle   = 15.sp   // list item titles, control labels
    val TextHeading = 19.sp   // sheet header ("Settings", "Video Quality"...)
}

// ═══════════════════════════════════════════════════════════════════
//  StreamXCore — native bridge (unchanged)
// ═══════════════════════════════════════════════════════════════════
@Keep
object StreamXCore {
    init {
        val libraries = listOf(
            "c++_shared", "avutil", "swresample", "swscale", "avcodec",
            "avformat", "avfilter", "avdevice", "mpv", "streamx-native"
        )
        for (lib in libraries) {
            try { System.loadLibrary(lib); Log.d("StreamX", "✅ $lib") }
            catch (e: Throwable) { throw RuntimeException("Failed to load '$lib': ${e.message}") }
        }
    }

    @JvmStatic external fun getTmdbKey(): String
    @JvmStatic external fun initMpvEngine(appctx: Any?)
    @JvmStatic external fun playMpvVideo(path: String)
    @JvmStatic external fun setMpvSurface(surface: Surface?)
    @JvmStatic external fun setMpvSurfaceSize(width: Int, height: Int)
    @JvmStatic external fun toggleVulkanFSR(enable: Boolean)
    @JvmStatic external fun seekMpvVideo(seconds: Double)
    @JvmStatic external fun seekMpvAbsolute(position: Double)
    @JvmStatic external fun pauseMpvVideo(pause: Boolean)
    @JvmStatic external fun getMpvTime(): Double
    @JvmStatic external fun getMpvDuration(): Double
    @JvmStatic external fun commandNative(cmd: Array<String>)
    @JvmStatic external fun setPropertyStringNative(name: String, value: String)
    @JvmStatic external fun getPropertyStringNative(name: String): String
    @JvmStatic external fun getPropertyIntNative(name: String): Long
    @JvmStatic external fun getMpvCachePercent(): Int
    @JvmStatic external fun isMpvPausedForCache(): Boolean
    @JvmStatic external fun getTrackListNative(type: String): String
    @JvmStatic external fun checkDecodeCompat()
    @JvmStatic external fun getDecodeModeLabel(): String
    @JvmStatic external fun getDecodeDiagInfo(): String
    @JvmStatic external fun setForceSwDecode(force: Boolean)
    @JvmStatic external fun getForceSwDecode(): Boolean
    @JvmStatic external fun getActiveGpuContext(): String

    fun cycleSubtitles()                 = commandNative(arrayOf("cycle", "sub"))
    fun cycleAudio()                     = commandNative(arrayOf("cycle", "audio"))
    fun addExternalSubtitle(url: String) = commandNative(arrayOf("sub-add", url, "select"))
    fun setSubTrack(id: Int)             = setPropertyStringNative("sid", if (id < 0) "no" else id.toString())
    fun setAudioTrack(id: Int)           = setPropertyStringNative("aid", id.toString())
    fun setVideoFilter(vf: String)       = setPropertyStringNative("vf", vf)

    fun getTrackList(type: String): List<MpvTrack> {
        val raw = try { getTrackListNative(type) } catch (e: Exception) { return emptyList() }
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val p = entry.split("|")
            if (p.size < 3) null
            else MpvTrack(p[0].toIntOrNull() ?: return@mapNotNull null, p[1].ifBlank { "Track ${p[0]}" }, p[2] == "1")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Data models
// ═══════════════════════════════════════════════════════════════════
data class MpvTrack(val id: Int, val title: String, val selected: Boolean)

data class QualityPreset(
    val label: String, val subtitle: String, val vf: String,
    val scale: String, val cscale: String, val deband: Boolean
)

val GPU_QUALITY_PRESETS = listOf(
    QualityPreset("Auto",       "Balanced (Recommended)", "",               "bilinear",         "bilinear",         false),
    QualityPreset("Cinematic",  "Best quality, High GPU", "",               "ewa_lanczossharp", "ewa_lanczossharp", true),
    QualityPreset("High",       "Sharp, Medium GPU",      "",               "spline36",         "spline36",         false),
    QualityPreset("Medium",     "Smooth, Low GPU",        "",               "bilinear",         "bilinear",         false),
    QualityPreset("720p Scale", "Force 720p render",      "scale=1280:720", "bilinear",         "bilinear",         false),
    QualityPreset("480p Scale", "Force 480p, Save battery","scale=854:480", "bilinear",         "bilinear",         false),
)

// ── Adaptive-quality step-down ladder ──────────────────────────────────
// Used ONLY while the user's selected preset is "Auto" — ordered from
// what Auto starts at down to the cheapest render cost. Auto itself
// starts at index 0 here (same render settings as the "Auto" entry
// above), and sustained stutter (see isStuttering/stutterStreak) steps
// DOWN one index at a time, same pattern as YouTube/Netflix's ABR ladder
// stepping down a rung rather than jumping straight to the bottom.
//
// Deliberately does NOT include "Cinematic" — that tier is strictly
// MORE expensive than Auto's own settings and would never be reached by
// stepping down from a strain condition; it's a manual opt-in-only tier
// for users who explicitly want maximum quality regardless of GPU cost.
val ADAPTIVE_LADDER = listOf(
    GPU_QUALITY_PRESETS[0], // Auto        (bilinear)      — starting point
    GPU_QUALITY_PRESETS[3], // Medium      (bilinear)      — same scaler as Auto but deband off, next logical step
    GPU_QUALITY_PRESETS[4], // 720p Scale  (bilinear + downscale) — real render-cost reduction
    GPU_QUALITY_PRESETS[5], // 480p Scale  (bilinear + downscale) — floor
)


data class SubtitleLanguage(val code: String, val label: String, val flag: String)

val SUBTITLE_LANGUAGES = listOf(
    SubtitleLanguage("eng", "English",    "🇺🇸"),
    SubtitleLanguage("ben", "Bengali",    "🇧🇩"),
    SubtitleLanguage("hin", "Hindi",      "🇮🇳"),
    SubtitleLanguage("ara", "Arabic",     "🇸🇦"),
    SubtitleLanguage("spa", "Spanish",    "🇪🇸"),
    SubtitleLanguage("fre", "French",     "🇫🇷"),
    SubtitleLanguage("ger", "German",     "🇩🇪"),
    SubtitleLanguage("por", "Portuguese", "🇧🇷"),
    SubtitleLanguage("rus", "Russian",    "🇷🇺"),
    SubtitleLanguage("tur", "Turkish",    "🇹🇷"),
    SubtitleLanguage("chi", "Chinese",    "🇨🇳"),
    SubtitleLanguage("jpn", "Japanese",   "🇯🇵"),
    SubtitleLanguage("kor", "Korean",     "🇰🇷"),
)

data class SubtitleStyle(
    val textColor:       Color          = Color.White,
    val backgroundColor: Color          = Color.Black.copy(alpha = 0.6f),
    val fontSize:        Int            = 18,
    val fontWeight:      FontWeight     = FontWeight.Bold,
    val showBackground:  Boolean        = true,
    val position:        SubtitlePosition = SubtitlePosition.BOTTOM
)

enum class SubtitlePosition { BOTTOM, TOP, CENTER }

data class ColorPreset(val name: String, val text: Color, val bg: Color)

val SUBTITLE_COLOR_PRESETS = listOf(
    ColorPreset("Classic",  Color.White,       Color.Black.copy(0.65f)),
    ColorPreset("Yellow",   Color(0xFFFFE500), Color.Black.copy(0.65f)),
    ColorPreset("Cyan",     Color.Cyan,        Color.Black.copy(0.65f)),
    ColorPreset("Green",    Color(0xFF00E676), Color.Black.copy(0.65f)),
    ColorPreset("Orange",   Color(0xFFFF9800), Color.Black.copy(0.65f)),
    ColorPreset("Pink",     Color(0xFFFF4081), Color.Black.copy(0.65f)),
    ColorPreset("No BG",    Color.White,       Color.Transparent),
    ColorPreset("Dark BG",  Color.White,       Color.Black.copy(0.90f)),
)

// ═══════════════════════════════════════════════════════════════════
//  Helper functions
// ═══════════════════════════════════════════════════════════════════
fun applyQualityPreset(preset: QualityPreset) {
    try {
        StreamXCore.setPropertyStringNative("scale",  preset.scale)
        StreamXCore.setPropertyStringNative("cscale", preset.cscale)
        StreamXCore.setPropertyStringNative("deband", if (preset.deband) "yes" else "no")
        StreamXCore.setVideoFilter(preset.vf)
    } catch (e: Exception) { Log.e("MPV", "quality: ${e.message}") }
}

fun isLiveCaptionEnabled(context: Context): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        Settings.Secure.getInt(context.contentResolver, "accessibility_captioning_enabled", 0) == 1
    else false
} catch (e: Exception) { false }

fun openLiveCaptionSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_CAPTIONING_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    } catch (e: Exception) {
        try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }) }
        catch (ex: Exception) { Toast.makeText(context, "Cannot open settings", Toast.LENGTH_SHORT).show() }
    }
}

private const val PREFS_NAME   = "streamx_prefs"
// Tier 2 #13: stutter/frame-drop detection tuning.
// Check every ~2s (smooths out single-frame drops, which are normal and
// imperceptible); flag as "stuttering" only if drops accumulate faster
// than ~2.5/sec sustained over that window — occasional 1-2 frame drops
// during a fast pan are inaudible/invisible and shouldn't alarm the user.
private const val STUTTER_CHECK_WINDOW_MS = 2000L
private const val STUTTER_DROP_THRESHOLD  = 5L
private const val PREF_SUB_LANG = "sub_lang_code"
private const val PREF_FORCE_SW_DECODE = "force_sw_decode"

fun getSavedSubLang(context: Context): SubtitleLanguage {
    val code = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_SUB_LANG, "eng") ?: "eng"
    return SUBTITLE_LANGUAGES.firstOrNull { it.code == code } ?: SUBTITLE_LANGUAGES[0]
}

fun saveSubLang(context: Context, lang: SubtitleLanguage) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREF_SUB_LANG, lang.code).apply()
}

// ── Force-SW-decode persistence ─────────────────────────────────
// Manual, per-device override for the residual class of broken HW
// decoders that produce a black frame even on ordinary 8-bit content
// — undetectable via pixel format or resolution heuristics. Once the
// user enables this in Settings, EVERY future video on this device
// starts in software decode mode automatically, with no re-detection
// needed. This is read at player startup (see LaunchedEffect(Unit) in
// MoviePlayerScreen) and applied via StreamXCore.setForceSwDecode().
fun getSavedForceSwDecode(context: Context): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_FORCE_SW_DECODE, false)

fun saveForceSwDecode(context: Context, force: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_FORCE_SW_DECODE, force).apply()
}

fun autoSubtitle(title: String, imdbId: String, context: Context, onStatus: (String) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        delay(2500)
        val tracks = try { StreamXCore.getTrackList("sub") } catch (e: Exception) { emptyList() }
        withContext(Dispatchers.Main) {
            if (tracks.isNotEmpty()) {
                try { StreamXCore.setSubTrack(tracks.first().id) } catch (e: Exception) {}
                onStatus("")
            } else {
                val lang = getSavedSubLang(context)
                onStatus("Loading ${lang.flag} subtitle…")
                fetchSubtitle(title, imdbId, lang.code, context) { msg -> onStatus(msg) }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
//  fetchSubtitle — FIXED (real-time download bug)
//
//  Root causes this fixes:
//   1. imdbId never reached the player before — MovieLinkSelectionScreen
//      had it, but the nav route to the player only carried the magnet
//      URL. Every subtitle search fell back to unreliable title-only
//      matching. The player now receives imdbId via the route and
//      passes it through to SubtitleRepository.search() for a precise
//      lookup when available.
//   2. StreamXCore.addExternalSubtitle() handed mpv a raw remote URL.
//      mpv's own HTTP fetch for "sub-add" has no success/failure signal
//      that reaches Kotlin — a timeout, 404, or an HTML error page
//      returned instead of an .srt file all failed *silently*, while
//      the UI still showed "✓ Subtitle loaded!". Now the file is
//      downloaded and content-verified here first (see
//      SubtitleRepository.download()), and only a verified local file
//      is handed to mpv — which mpv can then load synchronously and
//      reliably, no network round-trip needed at that point.
//
//  Language code mapping: MoviePlayerScreen uses ISO 639-2/B 3-letter
//  codes (eng, hin, ben…). SubtitleRepository / Stremio use 2-letter
//  ISO 639-1 codes (en, hi, bn…). ISO639_3TO2 handles the mapping.
// ──────────────────────────────────────────────────────────────────

private val ISO639_3TO2 = mapOf(
    "eng" to "en", "ben" to "bn", "hin" to "hi", "ara" to "ar",
    "spa" to "es", "fre" to "fr", "ger" to "de", "por" to "pt",
    "rus" to "ru", "tur" to "tr", "chi" to "zh", "jpn" to "ja",
    "kor" to "ko"
)

private fun fetchSubtitle(title: String, imdbId: String, langCode: String, context: Context, onComplete: (String) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val lang2   = ISO639_3TO2[langCode] ?: langCode.take(2)
            val validImdb = imdbId.trim().takeIf { it.startsWith("tt") }
            val results = SubtitleRepository.search(
                imdbId   = validImdb,
                title    = title,
                type     = MovieType.MOVIE,
                langCode = lang2
            )

            if (results.isEmpty()) {
                withContext(Dispatchers.Main) { onComplete("No subtitle found for this language") }
                return@launch
            }

            // Try candidates in ranked order until one actually downloads
            // and verifies as real subtitle content — a single bad/dead
            // link (common with community-sourced subtitle mirrors)
            // should not make the whole fetch look like "nothing found".
            var lastFailureReason = "No subtitle found for this language"
            for (candidate in results.take(5)) {
                when (val outcome = SubtitleRepository.download(context, candidate)) {
                    is SubtitleRepository.DownloadOutcome.Success -> {
                        withContext(Dispatchers.Main) {
                            try {
                                // Local file:// path — mpv loads this
                                // synchronously, no network fetch needed,
                                // so no more silent sub-add failures.
                                StreamXCore.addExternalSubtitle("file://${outcome.file.absolutePath}")
                                onComplete("✓ Subtitle loaded!")
                            } catch (e: Exception) {
                                Log.e("SubtitleFetch", "sub-add failed: ${e.message}")
                                onComplete("Couldn't load subtitle into player")
                            }
                        }
                        return@launch
                    }
                    is SubtitleRepository.DownloadOutcome.Failure -> {
                        Log.w("SubtitleFetch", "Candidate ${candidate.url} failed: ${outcome.reason}")
                        lastFailureReason = "Download failed: ${outcome.reason}"
                        // fall through, try next candidate
                    }
                }
            }

            withContext(Dispatchers.Main) { onComplete(lastFailureReason) }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onComplete("Error: ${e.message?.take(50)}") }
        }
    }
}

private fun applySubtitleStyleToMpv(style: SubtitleStyle) {
    fun colorToMpvHex(c: Color) = String.format("&H%02X%02X%02X%02X&",
        (c.alpha * 255).toInt(), (c.blue * 255).toInt(), (c.green * 255).toInt(), (c.red * 255).toInt())
    try {
        StreamXCore.setPropertyStringNative("sub-font-size", style.fontSize.toString())
        StreamXCore.setPropertyStringNative("sub-color", colorToMpvHex(style.textColor))
        if (style.showBackground) {
            StreamXCore.setPropertyStringNative("sub-back-color",  colorToMpvHex(style.backgroundColor))
            StreamXCore.setPropertyStringNative("sub-border-size", "2")
        } else {
            StreamXCore.setPropertyStringNative("sub-back-color",  "&H00000000&")
            StreamXCore.setPropertyStringNative("sub-border-size", "0")
        }
        StreamXCore.setPropertyStringNative("sub-bold",
            if (style.fontWeight == FontWeight.Bold || style.fontWeight == FontWeight.ExtraBold) "yes" else "no")
        StreamXCore.setPropertyStringNative("sub-pos",
            when (style.position) { SubtitlePosition.TOP -> "10"; SubtitlePosition.CENTER -> "50"; SubtitlePosition.BOTTOM -> "90" })
    } catch (e: Exception) { Log.e("SubStyle", e.message ?: "") }
}

private fun formatTime(secs: Long): String {
    val s = secs % 60; val m = (secs / 60) % 60; val h = secs / 3600
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

// ═══════════════════════════════════════════════════════════════════
//  Main Composable
// ═══════════════════════════════════════════════════════════════════
@Composable
fun MoviePlayerScreen(navController: NavController, encodedUrl: String, imdbId: String = "") {
    val context  = LocalContext.current
    val activity = context as? Activity
    val decodedUrl = remember { try { URLDecoder.decode(encodedUrl, "UTF-8") } catch (e: Exception) { encodedUrl } }
    val scope    = rememberCoroutineScope()

    val movieTitle = remember {
        if (decodedUrl.startsWith("magnet:?")) {
            try { (Uri.parse(decodedUrl).getQueryParameter("dn") ?: "")
                .replace(Regex("\\b(1080p|720p|480p|BluRay|WEB-DL|HDR|x264|x265|\\d{4}).*"), "").trim()
            } catch (e: Exception) { "" }
        } else ""
    }

    // ── Player state ──────────────────────────────────────────────
    var videoPath          by remember { mutableStateOf<String?>(null) }
    var isPlaying          by remember { mutableStateOf(true) }
    var currentTime        by remember { mutableDoubleStateOf(0.0) }
    var totalDuration      by remember { mutableDoubleStateOf(0.0) }
    var isPreBuffering     by remember { mutableStateOf(true) }
    // Root cause of a real "00:00" regression (confirmed twice via the
    // in-app "Copy Diagnostics" button): the old ERROR handler set
    // isPreBuffering=false, which hides the ONLY overlay that renders
    // statusMsg — so the error message was set but never actually
    // visible, and bare player controls showed instead with nothing
    // loaded. Fix: on ERROR, isPreBuffering STAYS true (so the overlay —
    // and its message — stay visible); this flag instead switches that
    // overlay from a loading spinner to an error icon + the actual
    // reason + a manual retry option.
    var isErrorState       by remember { mutableStateOf(false) }
    // Bumped by the manual "Retry" button in the error overlay to force
    // LaunchedEffect(decodedUrl, retryTrigger) below to re-run, since
    // decodedUrl alone doesn't change on a manual retry.
    var retryTrigger       by remember { mutableStateOf(0) }
    var isMidBuffering     by remember { mutableStateOf(false) }
    var cachePercent       by remember { mutableIntStateOf(100) }
    var statusMsg          by remember { mutableStateOf("Preparing...") }
    var downloadSpeed      by remember { mutableStateOf("0 KB/s") }
    var seeds              by remember { mutableIntStateOf(0) }
    var torrentProgress    by remember { mutableIntStateOf(0) }
    var isSurfaceReady     by remember { mutableStateOf(false) }
    // Which URL was most recently passed to playMpvVideo().
    // Used to guard against stale MPV duration/time from a previous video
    // prematurely dismissing the loading overlay before the new file loads.
    var mpvPath            by remember { mutableStateOf<String?>(null) }
    // Real-time HW/SW decode mode label for the settings UI.
    // Updated from the same 250ms poll loop that already tracks time/duration.
    var decodeModeLabel    by remember { mutableStateOf("\u2014") }
    // Tier 2 #13: Stutter/frame-drop indicator. Tracks the total dropped-frame
    // count (VO-level "framedrop=vo" drops + decoder-level drops) and its rate
    // of change — a sudden burst indicates the device is struggling to keep
    // up (thermal throttling, CPU/GPU contention from other apps, or a
    // problematic file), distinct from the black-frame class of bugs this
    // file already handles. Shown as a subtle indicator, never blocks playback.
    var isStuttering       by remember { mutableStateOf(false) }
    var lastDropCount      by remember { mutableStateOf(0L) }
    var lastDropCheckTime  by remember { mutableStateOf(0L) }
    // Tier 2 #14: Battery-saver awareness. When Android's Power Save mode is
    // active, the heavy ewa_lanczossharp GPU scaler (toggleVulkanFSR) is
    // disabled in favor of the cheap bilinear scaler — meaningful battery
    // savings over a 2h movie with negligible visual difference, and
    // respects the user's/system's explicit power-saving intent rather
    // than silently ignoring it.
    var isPowerSaveActive  by remember { mutableStateOf(false) }
    var surfaceW           by remember { mutableIntStateOf(0) }
    var surfaceH           by remember { mutableIntStateOf(0) }
    var isControlsVisible  by remember { mutableStateOf(true) }
    var isLocked           by remember { mutableStateOf(false) }
    var showSettingsMenu   by remember { mutableStateOf(false) }
    var activeSettingPage  by remember { mutableStateOf("Main") }
    var selectedQuality    by remember { mutableStateOf(GPU_QUALITY_PRESETS[0]) }
    // ── Adaptive quality (auto-downgrade under sustained GPU strain) ────────
    // Real-world observation (Redmi 15C / Mali-G52 / Helio G81 Ultra, 6GB):
    // most movies play perfectly smoothly at Auto, but some high-complexity
    // sources push the GPU scaler past what the device can sustain — the
    // EXISTING stutter detector (isStuttering, based on real mpv frame-drop
    // counters) already correctly flags this, but previously nothing acted
    // on it automatically; the user had to notice, open Settings, and
    // manually step down to Medium themselves.
    //
    // This mirrors the well-known adaptive-bitrate pattern (YouTube/Netflix
    // step DOWN a rung under real strain, never jump straight to the
    // bottom, and don't fight the user's own manual choice) but adapted to
    // what actually varies here: this is a local file/torrent stream with
    // a FIXED bitrate already downloaded — there's no network ABR ladder to
    // switch between. What actually varies under strain is RENDER cost
    // (scaler algorithm, debanding, output resolution), which is exactly
    // what GPU_QUALITY_PRESETS already controls. So "adaptive quality" here
    // means adaptively stepping down GPU_QUALITY_PRESETS's render-cost
    // ladder, not switching between differently-encoded source files.
    var autoQualityTier    by remember { mutableIntStateOf(0) }   // index into ADAPTIVE_LADDER while in Auto mode
    var stutterStreak      by remember { mutableIntStateOf(0) }   // consecutive stuttering check-windows, for debounce
    var showAutoDowngradeToast by remember { mutableStateOf(false) }
    var autoDowngradeLabel by remember { mutableStateOf("") }

    var subTracks          by remember { mutableStateOf<List<MpvTrack>>(emptyList()) }
    var isSearchingSub     by remember { mutableStateOf(false) }
    var subSearchMsg       by remember { mutableStateOf("") }
    var volumeLevel        by remember { mutableFloatStateOf(0.5f) }
    var brightnessLevel    by remember { mutableFloatStateOf(0.5f) }
    var gestureIcon        by remember { mutableStateOf<ImageVector?>(null) }
    var gestureText        by remember { mutableStateOf("") }
    var showGestureOverlay by remember { mutableStateOf(false) }
    var forwardAnimAlpha   by remember { mutableFloatStateOf(0f) }
    var rewindAnimAlpha    by remember { mutableFloatStateOf(0f) }
    var isSeeking          by remember { mutableStateOf(false) }
    var seekPreviewTime    by remember { mutableDoubleStateOf(0.0) }
    var selectedSubLang    by remember { mutableStateOf(getSavedSubLang(context)) }
    var isSubtitleEnabled  by remember { mutableStateOf(true) }
    var autoSubMsg         by remember { mutableStateOf("") }
    var showAutoSubMsg     by remember { mutableStateOf(false) }
    var subtitleStyle      by remember { mutableStateOf(SubtitleStyle()) }
    var showLiveCaptionBanner by remember { mutableStateOf(false) }
    var liveCaptionEnabled by remember { mutableStateOf(false) }

    // ── AI Scene Explainer state ──────────────────────────────────
    // ── Audio / system ────────────────────────────────────────────
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume    = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // ── Init MPV + system UI ─────────────────────────────────────
    // ── Tier 2 #14: Battery-saver-aware scaling ──────────────────────
    // Disables the heavy ewa_lanczossharp GPU scaler while Android's
    // Power Save mode is active — meaningful battery savings over a
    // long movie, negligible visual difference, and respects the
    // user's/system's explicit power-saving choice instead of silently
    // ignoring it. Reacts in real time if power-save is toggled mid-
    // playback (e.g. auto-triggered when battery drops below a
    // threshold), not just checked once at startup.
    DisposableEffect(Unit) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        fun applyPowerSaveState() {
            val active = powerManager?.isPowerSaveMode ?: false
            isPowerSaveActive = active
            try { StreamXCore.toggleVulkanFSR(!active) } catch (e: Exception) {}
        }

        applyPowerSaveState() // initial check when the player opens

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                applyPowerSaveState()
            }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))

        onDispose {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        // Apply the persisted "force software decode" override BEFORE
        // initMpvEngine() — init_mpv_engine reads s_force_sw_decode to
        // decide the very first hwdec setting, so this must happen first.
        try { StreamXCore.setForceSwDecode(getSavedForceSwDecode(context)) } catch (e: Exception) {}
        try { StreamXCore.initMpvEngine(context.applicationContext) }
        catch (e: Exception) { Log.e("MPV", "Init failed", e) }
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }?.hide(WindowInsetsCompat.Type.systemBars())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            liveCaptionEnabled = isLiveCaptionEnabled(context)
            if (!liveCaptionEnabled) { delay(3000); showLiveCaptionBanner = true }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try { StreamXCore.pauseMpvVideo(true) } catch (e: Exception) {}
            // stopAndClearCache() dispatches the actual native stop+clear work
            // onto TorrentEngine's background scope and returns immediately —
            // onDispose {} is a plain non-suspend lambda that Compose runs
            // synchronously on the main thread, so it must never block on
            // native I/O directly (see TorrentEngine.stop() fix comment).
            TorrentEngine.stopAndClearCache(context)
        }
    }

    // ── Torrent / direct URL ──────────────────────────────────────
    LaunchedEffect(decodedUrl, retryTrigger) {
        var retryCount = 0; val maxRetries = 3
        while (retryCount < maxRetries) {
            // Reset from any previous attempt's error state before trying
            // again — otherwise a successful retry would still show the
            // stale error icon/message for a frame or two.
            isErrorState   = false
            isPreBuffering = true
            if (decodedUrl.startsWith("magnet:?")) {
                // NOTE: no explicit clearCache() pre-call here anymore. Previously
                // this cleared the single shared torrents dir before every start(),
                // but that was a race-prone patch, not a fix — if it failed or
                // didn't finish before start() began downloading, the old movie's
                // file could still be picked by Rust's file selection and play
                // again. start() now allocates a fresh per-movie subfolder and
                // unconditionally removes any leftover sibling subfolders itself
                // (see TorrentEngine.allocateFreshMovieDir()), so the old movie's
                // data is guaranteed gone from the new torrent's directory before
                // a single byte of the new one downloads — not just "probably gone."
                val torrentsRoot = withContext(Dispatchers.IO) {
                    context.getExternalFilesDir("torrents")?.absolutePath
                        ?: context.cacheDir.absolutePath
                }
                var metadataTimeout = 0; var completed = false
                withContext(Dispatchers.IO) { TorrentEngine.start(decodedUrl, torrentsRoot) }
                TorrentEngine.status.collect { status ->
                    when (status.state) {
                        TorrentEngine.State.METADATA -> {
                            statusMsg = "Fetching metadata…"
                            if (++metadataTimeout > 240) {
                                statusMsg = if (retryCount < maxRetries - 1) "Timeout – retrying (${retryCount+1}/$maxRetries)" else "Timeout – last attempt"
                                TorrentEngine.stopAndAwait(); completed = true
                            }
                        }
                        TorrentEngine.State.BUFFERING -> { isPreBuffering = true; torrentProgress = status.progress; statusMsg = "Loading"; downloadSpeed = "${status.speedBps / 1000} KB/s"; seeds = status.seeds; metadataTimeout = 0 }
                        TorrentEngine.State.READY  -> {
                            // Do NOT set isPreBuffering=false here.
                            // The overlay stays until time-sync confirms MPV
                            // has loaded (duration>0) for the current videoPath.
                            videoPath = status.streamUrl
                            statusMsg = "Opening video…"
                            completed = true
                        }
                        TorrentEngine.State.ERROR  -> {
                            val reason = try { TorrentEngine.getLastError() } catch (e: Exception) { "" }
                            statusMsg = if (reason.isNotBlank()) reason else "Something went wrong starting this download."
                            isErrorState  = true
                            // isPreBuffering intentionally left true — see
                            // the variable's doc comment above. The overlay
                            // now shows an error icon + this message + a
                            // retry option instead of a spinner.
                            completed = true
                        }
                        else -> {}
                    }
                    if (completed) return@collect
                }
                if (completed) break; retryCount++; delay(2000)
            } else {
                    videoPath = decodedUrl
                    statusMsg = "Opening video…"
                    break
                }
        }
        if (retryCount == maxRetries) statusMsg = "Failed after $maxRetries retries."
    }

    // Reset stale MPV tracking whenever videoPath changes.
    // Prevents old MPV duration (7200 s from prev video) from prematurely
    // hiding the loading overlay before the new file has been opened.
    LaunchedEffect(videoPath) {
        mpvPath       = null
        currentTime   = 0.0
        totalDuration = 0.0
        // Reset stutter tracking (Tier 2 #13) — drop counts are per-file
        // in mpv, so a fresh baseline is needed for each new video.
        isStuttering      = false
        lastDropCount     = 0L
        lastDropCheckTime = 0L
        // Reset adaptive-quality tracking for the new file too — a
        // downgrade decided for the PREVIOUS video's GPU/thermal state
        // shouldn't carry over and silently start the next video at a
        // lower tier than Auto's default without the user knowing why.
        if (selectedQuality.label == "Auto") {
            autoQualityTier = 0
            stutterStreak   = 0
        }
    }

    // ── Start playback ─────────────────────────────────────────────
    LaunchedEffect(videoPath, isSurfaceReady) {
        val path = videoPath ?: return@LaunchedEffect
        if (!isSurfaceReady) return@LaunchedEffect
        try { StreamXCore.playMpvVideo(path) } catch (e: Exception) {
            Log.e("MPV", "playMpvVideo: ${e.message}")
            return@LaunchedEffect
        }
        // Record that playMpvVideo was called for `path`.
        // Time-sync only clears the overlay when mpvPath == videoPath,
        // so stale duration from a previous session can never fire prematurely.
        withContext(Dispatchers.Main) {
            mpvPath       = path
            totalDuration = 0.0   // reset so old value doesn't trigger early dismiss
            currentTime   = 0.0
        }
        delay(200)
        if (surfaceW > 0 && surfaceH > 0) try { StreamXCore.setMpvSurfaceSize(surfaceW, surfaceH) } catch (e: Exception) {}

        autoSubtitle(movieTitle.ifBlank { "Movie" }, imdbId, context) { msg ->
            if (msg.isNotEmpty()) { autoSubMsg = msg; showAutoSubMsg = true; scope.launch { delay(3000); showAutoSubMsg = false } }
        }

        repeat(20) {
            delay(1000)
            val dur = try { StreamXCore.getMpvDuration() } catch (e: Exception) { 0.0 }
            if (dur > 0) { Log.d("MPV", "Duration: $dur s"); return@LaunchedEffect }
        }
        try { StreamXCore.playMpvVideo(path) } catch (e: Exception) {}
    }

    // ── Time sync — always-running 250 ms poll ────────────────────────────
    LaunchedEffect(Unit) {
        while (true) {
            if (!isSeeking) {
                withContext(Dispatchers.IO) {
                    try {
                        val t = StreamXCore.getMpvTime()
                        val d = StreamXCore.getMpvDuration()

                        // Dynamic HW/SW decode compatibility check.
                        // No-op after the first file-load resolves (see
                        // check_decode_compatibility() in mpv_handler.cpp) —
                        // safe to call every tick.
                        try { StreamXCore.checkDecodeCompat() } catch (e: Exception) {}
                        val decMode = try { StreamXCore.getDecodeModeLabel() } catch (e: Exception) { "\u2014" }

                        // Tier 2 #13: Stutter/frame-drop tracking. Sums VO-level
                        // drops (framedrop=vo, timing-based) and decoder-level
                        // drops (hardware decoder falling behind) — both are
                        // confirmed mpv properties (player/command.c:
                        // "frame-drop-count" / "decoder-frame-drop-count").
                        // -1 sentinel means the property read failed (e.g. no
                        // file loaded yet) — treated as "no data" below, not
                        // as zero drops.
                        val dropsNow = try {
                            StreamXCore.getPropertyIntNative("frame-drop-count") +
                            StreamXCore.getPropertyIntNative("decoder-frame-drop-count")
                        } catch (e: Exception) { -1L }

                        withContext(Dispatchers.Main) {
                            if (t >= 0.0) currentTime = t
                            if (d > 0.0)  totalDuration = d
                            decodeModeLabel = decMode

                            if (dropsNow >= 0) {
                                val now = System.currentTimeMillis()
                                if (lastDropCheckTime == 0L) {
                                    // First sample after a (re)load — just establish
                                    // the baseline, nothing to compare against yet.
                                    lastDropCount     = dropsNow
                                    lastDropCheckTime = now
                                } else if (now - lastDropCheckTime >= STUTTER_CHECK_WINDOW_MS) {
                                    val delta = dropsNow - lastDropCount
                                    isStuttering  = delta >= STUTTER_DROP_THRESHOLD
                                    lastDropCount     = dropsNow
                                    lastDropCheckTime = now

                                    // ── Adaptive quality: step down under sustained strain ──
                                    // Only acts while the user is on "Auto" — a manual
                                    // selection (Cinematic/High/Medium/720p/480p) is
                                    // explicit user intent and is NEVER overridden
                                    // automatically. This is the actual answer to "can
                                    // this work like YouTube's auto quality" — same
                                    // debounced step-down behavior, applied to render
                                    // cost (scaler/resolution) instead of network
                                    // bitrate, since that's what this player's Auto mode
                                    // actually controls.
                                    if (selectedQuality.label == "Auto") {
                                        if (isStuttering) {
                                            stutterStreak++
                                            // Require 2 CONSECUTIVE stuttering windows
                                            // (~4s of real sustained strain, not one
                                            // brief hiccup — a single dropped-frame burst
                                            // from a scene cut or a momentary system
                                            // blip shouldn't trigger a quality change)
                                            // before actually stepping down.
                                            if (stutterStreak >= 2 && autoQualityTier < ADAPTIVE_LADDER.size - 1) {
                                                autoQualityTier++
                                                val next = ADAPTIVE_LADDER[autoQualityTier]
                                                applyQualityPreset(next)
                                                autoDowngradeLabel = next.label
                                                showAutoDowngradeToast = true
                                                stutterStreak = 0
                                                Log.d("MPV", "adaptive-quality: stepped down to ${next.label} after sustained strain")
                                            }
                                        } else {
                                            // Playback recovered for at least one full
                                            // window — reset the streak so a single past
                                            // stutter doesn't count toward a future,
                                            // unrelated strain episode. Deliberately does
                                            // NOT step back UP automatically: silently
                                            // raising render cost again is exactly the
                                            // quality-ladder flip-flopping that makes
                                            // some ABR systems feel unstable. The user's
                                            // next video starts fresh at Auto's top tier
                                            // (autoQualityTier resets on new file load),
                                            // and manually re-selecting Auto from the
                                            // settings sheet also resets it immediately.
                                            stutterStreak = 0
                                        }
                                    }
                                }
                            }

                            // ── Loading overlay dismiss gate ────────────────────
                            // Only clear isPreBuffering (show player) when:
                            //  1. mpvPath == videoPath: playMpvVideo() was called
                            //     for THIS path. Prevents stale MPV duration
                            //     from a previous video (e.g. 7200 s) triggering
                            //     an early dismiss before the new file loads.
                            //  2. totalDuration > 0: container header parsed.
                            //     For MKV this is near-instant (header at start).
                            //     For MP4 this waits for moov via FileStream
                            //     priority download — seconds not hours.
                            //  3. OR currentTime > 0.5: playback has advanced
                            //     even without known duration (TS live streams).
                            if (isPreBuffering
                                && mpvPath != null
                                && mpvPath == videoPath
                                && (totalDuration > 0.0 || currentTime > 0.5)) {
                                isPreBuffering = false
                                statusMsg      = ""
                            }
                        }
                        if (t > 0.0) TorrentEngine.updatePlaybackPosition(t)
                    } catch (e: Exception) { /* MPV not ready */ }
                }
            }
            delay(250)
        }
    }


    // ── Mid-play cache monitor ────────────────────────────────────
    LaunchedEffect(videoPath) {
        if (videoPath == null) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) {
                try {
                    val buffering = StreamXCore.isMpvPausedForCache(); val pct = StreamXCore.getMpvCachePercent()
                    withContext(Dispatchers.Main) { isMidBuffering = buffering; cachePercent = pct }
                } catch (e: Exception) {}
            }
            delay(500)
        }
    }

    LaunchedEffect(isControlsVisible, showSettingsMenu) {
        if (isControlsVisible && !showSettingsMenu) { delay(4000); isControlsVisible = false }
    }

    // ═══════════════════════════════════════════════════════════════
    //  UI
    // ═══════════════════════════════════════════════════════════════
    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // MPV SurfaceView
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            try { StreamXCore.setMpvSurface(h.surface) } catch (e: Exception) {}
                            isSurfaceReady = true
                            val sf = h.surfaceFrame
                            if (sf.width() > 0 && sf.height() > 0) {
                                surfaceW = sf.width(); surfaceH = sf.height()
                                try { StreamXCore.setMpvSurfaceSize(sf.width(), sf.height()) } catch (e: Exception) {}
                            }
                        }
                        override fun surfaceChanged(h: SurfaceHolder, fmt: Int, w: Int, ht: Int) {
                            surfaceW = w; surfaceH = ht; try { StreamXCore.setMpvSurfaceSize(w, ht) } catch (e: Exception) {}
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            isSurfaceReady = false; try { StreamXCore.setMpvSurface(null) } catch (e: Exception) {}
                        }
                    })
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        var lastUpdateTime  by remember { mutableLongStateOf(0L) }
        var dragAccumulator by remember { mutableFloatStateOf(0f) }

        // Gesture layer
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (showSettingsMenu) showSettingsMenu = false else isControlsVisible = !isControlsVisible
                        },
                        onDoubleTap = { offset ->
                            if (isLocked || showSettingsMenu) return@detectTapGestures
                            val fwd = offset.x > size.width / 2
                            val target = (currentTime + if (fwd) 10.0 else -10.0).coerceIn(0.0, totalDuration)
                            try { StreamXCore.seekMpvAbsolute(target); currentTime = target } catch (e: Exception) {}
                            if (fwd) { forwardAnimAlpha = 1f; scope.launch { delay(500); forwardAnimAlpha = 0f } }
                            else     { rewindAnimAlpha  = 1f; scope.launch { delay(500); rewindAnimAlpha  = 0f } }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { showGestureOverlay = true; dragAccumulator = 0f },
                        onDragEnd   = { scope.launch { delay(500); showGestureOverlay = false }; dragAccumulator = 0f }
                    ) { change, dragAmount ->
                        if (isLocked || showSettingsMenu) return@detectVerticalDragGestures
                        dragAccumulator += dragAmount
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime > 50 && abs(dragAccumulator) > 5f) {
                            lastUpdateTime = now
                            if (change.position.x > size.width / 2) {
                                val delta = (-(dragAccumulator / (size.height / 2)) * maxVolume).toInt()
                                if (delta != 0) {
                                    val newV = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + delta).coerceIn(0, maxVolume)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newV, 0)
                                    volumeLevel = newV / maxVolume.toFloat()
                                    gestureIcon = Icons.Rounded.VolumeUp; gestureText = "${(volumeLevel * 100).toInt()}%"; dragAccumulator = 0f
                                }
                            } else {
                                brightnessLevel = (brightnessLevel - (dragAccumulator / (size.height / 2))).coerceIn(0.01f, 1f)
                                val lp = activity?.window?.attributes; lp?.screenBrightness = brightnessLevel; activity?.window?.attributes = lp
                                gestureIcon = Icons.Rounded.BrightnessMedium; gestureText = "${(brightnessLevel * 100).toInt()}%"; dragAccumulator = 0f
                            }
                        }
                    }
                }
        )

        // Gesture overlay
        if (showGestureOverlay) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .background(AeonPlayer.GlassFill, RoundedCornerShape(20.dp))
                    .border(1.dp, AeonPlayer.GlassBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 28.dp, vertical = 22.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    gestureIcon?.let { Icon(it, null, tint = AeonPlayer.Sky300, modifier = Modifier.size(32.dp)) }
                    Spacer(Modifier.height(10.dp))
                    Text(gestureText, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    // Mini progress track — real level, not just a number.
                    val level = gestureText.trimEnd('%').toFloatOrNull()?.div(100f) ?: 0f
                    Box(Modifier.width(90.dp).height(3.dp).background(Color.White.copy(0.15f), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0f, 1f)).background(AeonPlayer.BrandGradient, RoundedCornerShape(2.dp)))
                    }
                }
            }
        }

        // Double-tap anim
        if (rewindAnimAlpha  > 0) Box(Modifier.align(Alignment.CenterStart).padding(50.dp).alpha(rewindAnimAlpha).background(AeonPlayer.GlassFill, CircleShape).border(1.dp, AeonPlayer.GlassBorder, CircleShape).padding(18.dp)) { Icon(Icons.Rounded.FastRewind,  null, tint = AeonPlayer.Sky300, modifier = Modifier.size(36.dp)) }
        if (forwardAnimAlpha > 0) Box(Modifier.align(Alignment.CenterEnd).padding(50.dp).alpha(forwardAnimAlpha).background(AeonPlayer.GlassFill, CircleShape).border(1.dp, AeonPlayer.GlassBorder, CircleShape).padding(18.dp)) { Icon(Icons.Rounded.FastForward, null, tint = AeonPlayer.Sky300, modifier = Modifier.size(36.dp)) }

        // Adaptive-quality auto-downgrade notice — transient, auto-dismissing.
        // Tells the user WHY quality just changed (device couldn't sustain
        // the previous render cost) rather than letting it look like an
        // unexplained quality drop, while staying out of the way of
        // playback — auto-hides after a few seconds, same pattern as the
        // gesture overlay above.
        LaunchedEffect(showAutoDowngradeToast) {
            if (showAutoDowngradeToast) {
                delay(3500)
                showAutoDowngradeToast = false
            }
        }
        AnimatedVisibility(
            visible  = showAutoDowngradeToast,
            enter    = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit     = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(AeonPlayer.GlassFill, RoundedCornerShape(50))
                    .border(1.dp, AeonPlayer.GlassBorder, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Icon(Icons.Rounded.Speed, null, tint = AeonPlayer.Amber, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Quality lowered to $autoDowngradeLabel for smoother playback",
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                )
            }
        }

        // Pre-buffer overlay — futuristic loading experience
        if (isPreBuffering) {  // stays until mpvPath==videoPath && duration>0
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                if (isErrorState) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Box(
                            Modifier.size(72.dp).background(AeonPlayer.Red.copy(0.12f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.ErrorOutline, null, tint = AeonPlayer.Red, modifier = Modifier.size(34.dp))
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "Playback failed",
                            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            statusMsg,
                            color = Color.White.copy(0.6f), fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 280.dp),
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { retryTrigger++ },
                            colors = ButtonDefaults.buttonColors(containerColor = AeonPlayer.Sky300),
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp),
                        ) { Text("Try again", color = Color.Black, fontWeight = FontWeight.SemiBold) }
                    }
                } else {
                    FuturisticLoadingPanel(
                        percent       = if (torrentProgress > 0) torrentProgress else null,
                        downloadSpeed = downloadSpeed,
                        seeds         = seeds,
                    )
                }
                IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            }
        }

        // Mid-play buffering overlay — same futuristic language, compact
        if (!isPreBuffering && isMidBuffering && videoPath != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FuturisticLoadingPanel(
                    percent       = if (cachePercent in 0..99) cachePercent else null,
                    downloadSpeed = downloadSpeed,
                    seeds         = seeds,
                    compact       = true,
                )
            }
        }

        // Auto-sub status toast
        AnimatedVisibility(
            visible  = showAutoSubMsg,
            enter    = fadeIn() + slideInVertically { it / 2 },
            exit     = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 80.dp)
        ) {
            Row(Modifier.background(Color.Black.copy(0.75f), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = AeonPlayer.Sky300, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(autoSubMsg, color = Color.White, fontSize = 12.sp)
            }
        }

        // Live Caption banner
        AnimatedVisibility(
            visible  = showLiveCaptionBanner && !isControlsVisible,
            enter    = slideInVertically { -it } + fadeIn(),
            exit     = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
        ) {
            LiveCaptionBanner(
                onEnable  = { openLiveCaptionSettings(context); showLiveCaptionBanner = false },
                onDismiss = { showLiveCaptionBanner = false }
            )
        }

        // ── Player controls ───────────────────────────────────────
        if (!isPreBuffering) {  // only when MPV has loaded
            AnimatedVisibility(
                visible = isControlsVisible && !showSettingsMenu,
                enter   = fadeIn(), exit = fadeOut()
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.4f))) {

                    // Top bar
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp).align(Alignment.TopStart), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                            IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                            Text(
                                movieTitle.ifBlank { "" },
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 4.dp, end = 8.dp).weight(1f, fill = false),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (decodedUrl.startsWith("magnet")) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .padding(end = 10.dp)
                                        .background(Color.Black.copy(0.35f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Rounded.ArrowDownward, null, tint = AeonPlayer.Amber, modifier = Modifier.size(11.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text(downloadSpeed, color = Color.White.copy(0.85f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Rounded.People, null, tint = AeonPlayer.Green, modifier = Modifier.size(11.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("$seeds", color = Color.White.copy(0.85f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }

                            // Tier 2 #13: Stutter indicator — only visible while
                            // actively dropping frames at a noticeable rate.
                            // Auto-hides once drops stop; never blocks playback,
                            // purely informational so the user understands a
                            // hiccup is a device/network issue, not a broken app.
                            if (isStuttering) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .padding(end = 10.dp)
                                        .background(AeonPlayer.Amber.copy(0.9f), RoundedCornerShape(50))
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Icon(Icons.Rounded.Speed, null, tint = Color.Black, modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Playback lag", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }

                            // ── AI Scene Explain button (AeonCore v2.0) ──


                            // Live Caption button
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                IconButton(onClick = {
                                    liveCaptionEnabled = isLiveCaptionEnabled(context)
                                    if (!liveCaptionEnabled) openLiveCaptionSettings(context)
                                    else Toast.makeText(context, "Live Caption is ON ✓", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Rounded.ClosedCaption, null,
                                        tint     = if (liveCaptionEnabled) AeonPlayer.Sky300 else Color.White,
                                        modifier = Modifier.size(28.dp))
                                }
                            }

                            // Subtitle toggle
                            IconButton(onClick = {
                                isSubtitleEnabled = !isSubtitleEnabled
                                try { StreamXCore.setPropertyStringNative("sid", if (isSubtitleEnabled) "auto" else "no") } catch (e: Exception) {}
                                Toast.makeText(context, if (isSubtitleEnabled) "Subtitles ON" else "Subtitles OFF", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(if (isSubtitleEnabled) Icons.Rounded.Subtitles else Icons.Rounded.SubtitlesOff,
                                    null,
                                    tint     = if (isSubtitleEnabled) AeonPlayer.Sky300 else Color.White.copy(0.5f),
                                    modifier = Modifier.size(28.dp))
                            }

                            // NEW: quick-access Video Quality chip — graduates the
                            // single most frequently changed setting out of the
                            // sheet, following the same pattern Netflix/YouTube use
                            // for their top-bar quality selector. Tapping opens the
                            // settings sheet directly to the Quality page instead of
                            // the Main list, saving a tap for the common case.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(0.08f))
                                    .clickable { showSettingsMenu = true; activeSettingPage = "Quality" }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Rounded.HighQuality, null, tint = AeonPlayer.Sky300, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    if (selectedQuality.label == "Auto" && autoQualityTier > 0) ADAPTIVE_LADDER[autoQualityTier].label else selectedQuality.label,
                                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                                )
                            }

                            IconButton(onClick = { showSettingsMenu = true; activeSettingPage = "Main" }) {
                                Icon(Icons.Rounded.Settings, null, tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                    }

                    // Center controls
                    if (!isLocked) {
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                            val rewindInteraction = remember { MutableInteractionSource() }
                            val rewindPressed by rewindInteraction.collectIsPressedAsState()
                            val rewindScale by animateFloatAsState(if (rewindPressed) 0.88f else 1f, label = "rewindScale")
                            Box(
                                Modifier
                                    .size(52.dp)
                                    .scale(rewindScale)
                                    .background(Color.White.copy(0.10f), CircleShape)
                                    .clickable(interactionSource = rewindInteraction, indication = null) {
                                        val t = max(0.0, currentTime - 10.0); try { StreamXCore.seekMpvAbsolute(t); currentTime = t } catch (e: Exception) {}
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Replay10, null, tint = Color.White, modifier = Modifier.size(26.dp)) }

                            val playInteraction = remember { MutableInteractionSource() }
                            val playPressed by playInteraction.collectIsPressedAsState()
                            val playScale by animateFloatAsState(if (playPressed) 0.92f else 1f, label = "playScale")
                            Box(
                                Modifier
                                    .size(76.dp)
                                    .scale(playScale)
                                    .background(AeonPlayer.BrandGradient, CircleShape)
                                    .clickable(interactionSource = playInteraction, indication = null) {
                                        isPlaying = !isPlaying; try { StreamXCore.pauseMpvVideo(!isPlaying) } catch (e: Exception) {}
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    null, tint = Color.White,
                                    modifier = Modifier.size(38.dp),
                                )
                            }

                            val forwardInteraction = remember { MutableInteractionSource() }
                            val forwardPressed by forwardInteraction.collectIsPressedAsState()
                            val forwardScale by animateFloatAsState(if (forwardPressed) 0.88f else 1f, label = "forwardScale")
                            Box(
                                Modifier
                                    .size(52.dp)
                                    .scale(forwardScale)
                                    .background(Color.White.copy(0.10f), CircleShape)
                                    .clickable(interactionSource = forwardInteraction, indication = null) {
                                        val t = min(totalDuration, currentTime + 10.0); try { StreamXCore.seekMpvAbsolute(t); currentTime = t } catch (e: Exception) {}
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Forward10, null, tint = Color.White, modifier = Modifier.size(26.dp)) }
                        }
                    }

                    // Lock
                    IconButton(onClick = { isLocked = !isLocked }, modifier = Modifier.align(Alignment.CenterEnd).padding(32.dp)) {
                        Icon(if (isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = if (isLocked) AeonPlayer.Red else Color.White)
                    }

                    // Seek bar
                    if (!isLocked) {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                            val displayTime = if (isSeeking) seekPreviewTime else currentTime
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTime(displayTime.toLong()),   color = Color.White.copy(0.9f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(formatTime(totalDuration.toLong()), color = Color.White.copy(0.6f), fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(2.dp))
                            Slider(
                                value             = displayTime.toFloat(),
                                onValueChange     = { v -> isSeeking = true; seekPreviewTime = v.toDouble().coerceIn(0.0, totalDuration) },
                                onValueChangeFinished = {
                                    val target = seekPreviewTime.coerceIn(0.0, totalDuration)
                                    try { StreamXCore.seekMpvAbsolute(target); currentTime = target } catch (e: Exception) {}
                                    isSeeking = false
                                },
                                valueRange = 0f..max(1f, totalDuration.toFloat()),
                                // Custom track: brand-gradient fill instead of a flat
                                // color, quiet translucent remainder track — same
                                // drag/seek behavior as before (untouched), only the
                                // visual rendering changes.
                                track = { state ->
                                    val fraction = ((state.value - state.valueRange.start) /
                                        (state.valueRange.endInclusive - state.valueRange.start).coerceAtLeast(1f)).coerceIn(0f, 1f)
                                    Box(Modifier.fillMaxWidth().height(4.dp)) {
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .background(Color.White.copy(0.18f), RoundedCornerShape(2.dp))
                                        )
                                        Box(
                                            Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(fraction)
                                                .background(AeonPlayer.BrandGradient, RoundedCornerShape(2.dp))
                                        )
                                    }
                                },
                                thumb = {
                                    Box(
                                        Modifier
                                            .size(22.dp)
                                            .shadow(6.dp, CircleShape, ambientColor = AeonPlayer.Sky300, spotColor = AeonPlayer.Sky300)
                                            .background(AeonPlayer.Sky300.copy(0.22f), CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(Modifier.size(11.dp).background(Color.White, CircleShape))
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // ── Settings side-panel ───────────────────────────────
            AnimatedVisibility(
                visible  = showSettingsMenu,
                enter    = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit     = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(340.dp)
                        .background(AeonPlayer.GlassFill)
                        .border(width = 1.dp, brush = Brush.linearGradient(listOf(AeonPlayer.Sky300.copy(0.2f), Color.Transparent)), shape = RectangleShape)
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    Column {
                        // Header
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp)) {
                            if (activeSettingPage != "Main") {
                                Box(
                                    Modifier.size(32.dp).background(Color.White.copy(0.08f), CircleShape).clickable { activeSettingPage = "Main" },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(18.dp)) }
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(when (activeSettingPage) {
                                "Quality"     -> "Video Quality"
                                "Subtitles"   -> "Subtitles"
                                "SubStyle"    -> "Subtitle Style"
                                "SubLanguage" -> "Subtitle Language"
                                "Audio"       -> "Audio Tracks"
                                "LiveCaption" -> "Live Caption"
                                "DecodeInfo"  -> "Decode Mode"
                                else          -> "Settings"
                            }, color = Color.White, fontSize = AeonPlayer.TextHeading, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
                        }

                        LazyColumn {
                            item {
                                when (activeSettingPage) {
                                    "Main" -> {
                                        SettingsSectionLabel("Playback")
                                        SettingsItem(
                                            Icons.Rounded.HighQuality, "Video Quality",
                                            if (selectedQuality.label == "Auto" && autoQualityTier > 0)
                                                "Auto \u2192 ${ADAPTIVE_LADDER[autoQualityTier].label} (device-adjusted)"
                                            else selectedQuality.label
                                        ) { activeSettingPage = "Quality" }
                                        SettingsItem(Icons.Rounded.Memory,        "Decode Mode",     decodeModeLabel)                                      { activeSettingPage = "DecodeInfo" }

                                        SettingsSectionLabel("Subtitles & Audio")
                                        SettingsItem(Icons.Rounded.Subtitles,     "Subtitles",       "Tracks & Download")                                  { subTracks = StreamXCore.getTrackList("sub"); activeSettingPage = "Subtitles" }
                                        SettingsItem(Icons.Rounded.Translate,     "Sub Language",    selectedSubLang.flag + " " + selectedSubLang.label)   { activeSettingPage = "SubLanguage" }
                                        SettingsItem(Icons.Rounded.FormatSize,    "Subtitle Style",  "Color, size, font")                                  { activeSettingPage = "SubStyle" }
                                        SettingsItem(Icons.Rounded.LibraryMusic,  "Audio Track",     "Internal tracks")                                    { activeSettingPage = "Audio" }
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                            SettingsItem(Icons.Rounded.ClosedCaption, "Live Caption", if (liveCaptionEnabled) "Enabled ✓" else "Tap to enable") { activeSettingPage = "LiveCaption" }

                                        SettingsSectionLabel("Support")
                                        // Tier 3 #16: combines torrent-engine state (TorrentEngine.
                                        // getDiagnostics(), works in release builds — no HTTP surface,
                                        // unlike the debug_assertions-gated /debug route) with decode
                                        // diagnostics already on-screen, for a one-tap bug report.
                                        SettingsItem(Icons.Rounded.ContentCopy, "Copy Diagnostics", "For bug reports") {
                                            val report = buildString {
                                                appendLine("=== StreamX Diagnostics ===")
                                                appendLine("Decode mode: $decodeModeLabel")
                                                appendLine("--- Torrent engine ---")
                                                append(try { TorrentEngine.getDiagnostics() } catch (e: Exception) { "unavailable: ${e.message}\n" })
                                                appendLine("--- Decode diagnostics ---")
                                                append(try { StreamXCore.getDecodeDiagInfo() } catch (e: Exception) { "unavailable" })
                                            }
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            clipboard?.setPrimaryClip(ClipData.newPlainText("StreamX Diagnostics", report))
                                            Toast.makeText(context, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    "Quality" -> {
                                        Text("GPU Render Quality", color = AeonPlayer.Slate500, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
                                        GPU_QUALITY_PRESETS.forEach { preset ->
                                            val sel = selectedQuality.label == preset.label
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (sel) AeonPlayer.Sky300.copy(0.12f) else Color.Transparent)
                                                    .then(if (sel) Modifier.border(1.dp, AeonPlayer.Sky300.copy(0.35f), RoundedCornerShape(10.dp)) else Modifier)
                                                    .clickable {
                                                        selectedQuality = preset
                                                        applyQualityPreset(preset)
                                                        if (preset.label == "Auto") { autoQualityTier = 0; stutterStreak = 0 }
                                                        showSettingsMenu = false
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(preset.label,    color = if (sel) AeonPlayer.Sky300 else Color.White, fontSize = 15.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                                    Text(preset.subtitle, color = AeonPlayer.Slate500, fontSize = 11.sp)
                                                }
                                                if (sel) Icon(Icons.Rounded.Check, null, tint = AeonPlayer.Sky300, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                    "Subtitles" -> {
                                        Button(onClick = {
                                            if (!isSearchingSub) {
                                                isSearchingSub = true; subSearchMsg = "Searching…"
                                                fetchSubtitle(movieTitle.ifBlank { "Movie" }, imdbId, selectedSubLang.code, context) { msg ->
                                                    isSearchingSub = false; subSearchMsg = msg; subTracks = StreamXCore.getTrackList("sub")
                                                }
                                            }
                                        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AeonPlayer.Navy700)) {
                                            if (isSearchingSub) { CircularProgressIndicator(color = AeonPlayer.Sky300, modifier = Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Searching…", color = Color.White, fontSize = 12.sp) }
                                            else { Icon(Icons.Rounded.Download, null, tint = AeonPlayer.Sky300, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Download  ${selectedSubLang.flag} ${selectedSubLang.label}", color = Color.White) }
                                        }
                                        if (subSearchMsg.isNotEmpty()) Text(subSearchMsg, color = AeonPlayer.Sky300, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                                        Spacer(Modifier.height(18.dp))
                                        Text("SUBTITLE TRACKS", color = AeonPlayer.Slate500, fontSize = 10.sp, letterSpacing = 1.sp); Spacer(Modifier.height(8.dp))
                                        SubTrackRow("Disable", false) { StreamXCore.setSubTrack(-1); showSettingsMenu = false }
                                        if (subTracks.isEmpty()) Text("No tracks found", color = AeonPlayer.Slate500, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                                        else subTracks.forEach { t -> SubTrackRow(t.title, t.selected) { StreamXCore.setSubTrack(t.id); showSettingsMenu = false } }
                                    }
                                    "SubLanguage" -> {
                                        Text("SELECT LANGUAGE", color = AeonPlayer.Slate500, fontSize = 10.sp, letterSpacing = 1.sp)
                                        Text("Language for auto-download", color = AeonPlayer.Slate500.copy(0.7f), fontSize = 11.sp)
                                        Spacer(Modifier.height(12.dp))
                                        SUBTITLE_LANGUAGES.forEach { lang ->
                                            val sel = selectedSubLang.code == lang.code
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (sel) AeonPlayer.Sky300.copy(0.12f) else Color.Transparent)
                                                    .then(if (sel) Modifier.border(1.dp, AeonPlayer.Sky300.copy(0.35f), RoundedCornerShape(10.dp)) else Modifier)
                                                    .clickable {
                                                        selectedSubLang = lang; saveSubLang(context, lang)
                                                        isSearchingSub = true; subSearchMsg = "Loading ${lang.flag} ${lang.label}…"
                                                        fetchSubtitle(movieTitle.ifBlank { "Movie" }, imdbId, lang.code, context) { msg -> isSearchingSub = false; subSearchMsg = msg; subTracks = StreamXCore.getTrackList("sub") }
                                                        activeSettingPage = "Subtitles"
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(lang.flag, fontSize = 22.sp)
                                                Spacer(Modifier.width(12.dp))
                                                Text(lang.label, color = if (sel) AeonPlayer.Sky300 else Color.White, fontSize = 15.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                                if (sel) Icon(Icons.Rounded.Check, null, tint = AeonPlayer.Sky300, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                    "SubStyle" -> { SubtitleStylePage(style = subtitleStyle, onChange = { s -> subtitleStyle = s; applySubtitleStyleToMpv(s) }) }
                                    "Audio" -> {
                                        val audioTracks = remember(activeSettingPage) { StreamXCore.getTrackList("audio") }
                                        Text("AUDIO TRACKS", color = AeonPlayer.Slate500, fontSize = 10.sp, letterSpacing = 1.sp); Spacer(Modifier.height(8.dp))
                                        if (audioTracks.isEmpty()) Text("No audio tracks found", color = AeonPlayer.Slate500, fontSize = 12.sp)
                                        else audioTracks.forEach { t -> SubTrackRow(t.title, t.selected) { StreamXCore.setAudioTrack(t.id); showSettingsMenu = false } }
                                    }
                                    "LiveCaption" -> {
                                        LiveCaptionSettingsPage(
                                            isEnabled      = liveCaptionEnabled,
                                            onRefresh      = { liveCaptionEnabled = isLiveCaptionEnabled(context) },
                                            onOpenSettings = { openLiveCaptionSettings(context) }
                                        )
                                    }
                                    "DecodeInfo" -> {
                                        // diag = "<codec>|<pixelformat>|<hwdec-current>|<auto_switched>|<reason>"
                                        // Recomputed on every recomposition of this page while it's
                                        // visible, driven by decodeModeLabel changing every 250ms —
                                        // so this stays live if the page is left open across a seek
                                        // or a mid-playback auto-switch.
                                        val diag = remember(decodeModeLabel) {
                                            try { StreamXCore.getDecodeDiagInfo() } catch (e: Exception) { "" }
                                        }
                                        val parts        = diag.split("|")
                                        val codec         = parts.getOrNull(0)?.ifBlank { "Unknown" } ?: "Unknown"
                                        val pixfmt         = parts.getOrNull(1)?.ifBlank { "\u2014" } ?: "\u2014"
                                        val hwdecCurrent   = parts.getOrNull(2) ?: ""
                                        val autoSwitched   = parts.getOrNull(3) == "1"
                                        val reason         = parts.getOrNull(4) ?: ""
                                        val isHardware      = hwdecCurrent.isNotEmpty()

                                        var forceSwEnabled by remember {
                                            mutableStateOf(try { StreamXCore.getForceSwDecode() } catch (e: Exception) { false })
                                        }
                                        // GPU rendering backend (Vulkan/OpenGL) — reflects mpv's own
                                        // gpu-context="androidvk,android" probe result. Recomputed
                                        // alongside decodeModeLabel so it stays live if this page is
                                        // left open across a file switch.
                                        val gpuContext = remember(decodeModeLabel) {
                                            try { StreamXCore.getActiveGpuContext() } catch (e: Exception) { "\u2014" }
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                                            Icon(
                                                Icons.Rounded.Memory, null,
                                                tint = if (isHardware) AeonPlayer.Green else AeonPlayer.Amber,
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column {
                                                Text(decodeModeLabel, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                                Text(
                                                    if (isHardware) "Hardware-accelerated decoding" else "Software (CPU) decoding",
                                                    color = AeonPlayer.Slate500, fontSize = 11.sp
                                                )
                                            }
                                        }

                                        if (autoSwitched) {
                                            val reasonText = when (reason) {
                                                "10bit"        -> "this file uses a 10-bit color format that your device's hardware decoder can't render correctly (would show a black screen)."
                                                "oversized"    -> "this file's resolution exceeds what your device's hardware decoder reliably supports."
                                                "black-frame"  -> "your device's hardware decoder produced a blank frame for this file \u2014 confirmed by checking the actual picture."
                                                "black-frame-periodic" -> "your device's hardware decoder started producing a blank picture partway through this file (this can happen due to overheating) \u2014 confirmed by checking the actual picture."
                                                "log-detected" -> "your device's hardware decoder reported an error while starting this file."
                                                "manual"       -> "you've enabled \u201cAlways use software decoding\u201d below."
                                                "sw-also-black" -> "software decoding was tried but also produced a blank picture \u2014 this file may be corrupt or use an unsupported feature."
                                                else           -> "a hardware decoding issue was detected."
                                            }
                                            val isUnresolvedFailure = reason == "sw-also-black"
                                            val cardColor = if (isUnresolvedFailure) AeonPlayer.Red else AeonPlayer.Amber
                                            val cardPrefix = if (isUnresolvedFailure)
                                                "Playback issue \u2014 " else "Auto-switched to software decoding \u2014 "
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(cardColor.copy(0.12f), RoundedCornerShape(10.dp))
                                                    .padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    if (isUnresolvedFailure) Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
                                                    null, tint = cardColor, modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    "$cardPrefix$reasonText",
                                                    color = cardColor, fontSize = 11.sp, lineHeight = 15.sp
                                                )
                                            }
                                            Spacer(Modifier.height(14.dp))
                                        }

                                        Spacer(Modifier.height(6.dp))
                                        Text("DIAGNOSTICS", color = AeonPlayer.Slate500, fontSize = 10.sp, letterSpacing = 1.sp)
                                        Spacer(Modifier.height(8.dp))
                                        DecodeInfoRow("Codec",          codec)
                                        DecodeInfoRow("Pixel Format",   pixfmt)
                                        DecodeInfoRow("HW Decoder",     hwdecCurrent.ifEmpty { "Not active (software)" })
                                        DecodeInfoRow("Auto-switched",  if (autoSwitched) "Yes" else "No")
                                        DecodeInfoRow("GPU Rendering",  gpuContext)
                                        DecodeInfoRow("Battery Saver",  if (isPowerSaveActive) "On (using lighter scaling)" else "Off")
                                        DecodeInfoRow("Frame Drops",    if (isStuttering) "Active (device struggling)" else "Stable")

                                        Spacer(Modifier.height(24.dp))

                                        // ── Manual override ─────────────────────────────────
                                        // For the residual case automatic detection can't catch:
                                        // a broken decoder that still produces ordinary 8-bit
                                        // output but renders it wrong. If a user notices black
                                        // screens even after the automatic fixes above, this
                                        // toggle makes every future video on this device use
                                        // software decoding — persisted across app restarts.
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Always use software decoding", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                                Text(
                                                    "Turn this on if videos still show a black screen after the app's automatic fix. Uses more battery.",
                                                    color = AeonPlayer.Slate500, fontSize = 11.sp, lineHeight = 14.sp,
                                                    modifier = Modifier.padding(top = 2.dp, end = 12.dp)
                                                )
                                            }
                                            Switch(
                                                checked = forceSwEnabled,
                                                onCheckedChange = { checked ->
                                                    forceSwEnabled = checked
                                                    saveForceSwDecode(context, checked)
                                                    try { StreamXCore.setForceSwDecode(checked) } catch (e: Exception) {}
                                                },
                                                colors = SwitchDefaults.colors(checkedThumbColor = AeonPlayer.Sky300, checkedTrackColor = AeonPlayer.Sky300.copy(0.5f))
                                            )
                                        }

                                        Spacer(Modifier.height(16.dp))
                                        Text(
                                            "StreamX automatically detects video formats that render as a " +
                                            "black screen on hardware decoders (10-bit HDR content, oversized " +
                                            "frames, decoder errors, or by checking the actual picture) and " +
                                            "switches to software decoding for that file only \u2014 no settings " +
                                            "needed in most cases.",
                                            color = AeonPlayer.Slate500.copy(0.9f), fontSize = 11.sp, lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ══════════════════════════════════════════════════════════
        //  AeonCore v2.0 — Aura Frame Experience
        // ══════════════════════════════════════════════════════════
    }
}


// ═══════════════════════════════════════════════════════════════════
//  Futuristic Loading Experience
// ═══════════════════════════════════════════════════════════════════
//
// Replaces the old flat CircularProgressIndicator + separate
// LinearProgressIndicator + "Buffering N%" text + scattered green speed/
// seed text with ONE composed glassmorphic panel built around a single
// signature element: an orbital dual-ring that fills to the REAL buffer
// percentage (torrentProgress / cachePercent — both driven by actual
// downloaded bytes, never a fake/simulated animation).
//
// percent == null means "no real number yet" (metadata still resolving) —
// shown as a slow orbital sweep in the same visual language as the
// determinate ring, so the transition from "resolving" to "43%" reads as
// one continuous state rather than two different widgets swapping.
@Composable
private fun FuturisticLoadingPanel(
    percent: Int?,
    downloadSpeed: String,
    seeds: Int,
    compact: Boolean = false,
) {
    val ringSize = if (compact) 76.dp else 128.dp
    val panelPadding = if (compact) 22.dp else 36.dp

    Box(
        modifier = Modifier
            .padding(horizontal = 28.dp)
            .then(if (compact) Modifier.widthIn(max = 260.dp) else Modifier)
            .background(AeonPlayer.GlassFill, RoundedCornerShape(28.dp))
            .border(width = 1.dp, brush = AeonPlayer.GlassBorder, shape = RoundedCornerShape(28.dp))
            .padding(panelPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            OrbitalBufferRing(percent = percent, size = ringSize)

            Spacer(Modifier.height(if (compact) 14.dp else 22.dp))

            Text(
                "Loading",
                color = Color.White,
                fontSize = if (compact) 13.sp else 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
            )

            if (percent != null && percent in 1..99) {
                Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
                ConnectionStatRow(downloadSpeed = downloadSpeed, seeds = seeds, compact = compact)
            }
        }
    }
}

// The signature element: a dual-layer ring —
//   • outer ring: real progress, 0-100%, filled proportionally to the
//     ACTUAL buffered percentage (never simulated/fake)
//   • inner ring: a slim, continuously-orbiting accent arc, purely
//     decorative — this is what gives the "futuristic/alive" feel even
//     while the outer ring's real progress is barely moving on a slow
//     connection, without ever pretending the outer number is something
//     it isn't
//   • center: glowing percentage text, or a pulsing dot while resolving
@Composable
private fun OrbitalBufferRing(percent: Int?, size: Dp) {
    val animatedPercent by animateFloatAsState(
        targetValue   = (percent ?: 0).coerceIn(0, 100) / 100f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "orbitalRingProgress",
    )

    val infinite = rememberInfiniteTransition(label = "orbitalRingMotion")

    // Slow outer decorative orbit — always spinning, regardless of state,
    // to read as "actively working" rather than stalled.
    val orbitAngle by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "orbitAngle",
    )

    // Faster inner sweep, used both as the indeterminate indicator AND as
    // a constant "alive" accent layered under the determinate ring.
    val sweepAngle by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "sweepAngle",
    )

    // Gentle breathing glow for the center content while resolving.
    val pulse by infinite.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val outerStroke = Stroke(width = this.size.minDimension * 0.055f, cap = StrokeCap.Round)
            val innerStroke = Stroke(width = this.size.minDimension * 0.03f,  cap = StrokeCap.Round)

            val outerInset = outerStroke.width / 2f
            val outerArcSize = Size(this.size.width - outerStroke.width, this.size.height - outerStroke.width)

            val innerMargin = outerStroke.width * 1.8f
            val innerInset = innerMargin + innerStroke.width / 2f
            val innerArcSize = Size(this.size.width - innerInset * 2f, this.size.height - innerInset * 2f)

            // Outer track — quiet, always full circle.
            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(outerInset, outerInset), size = outerArcSize, style = outerStroke,
            )

            // Outer ring — REAL progress (or a soft full-brightness decorative
            // spin while percent is unknown, so it never implies a fake number).
            if (percent != null) {
                drawArc(
                    brush = Brush.sweepGradient(AeonPlayer.BrandSweep),
                    startAngle = -90f, sweepAngle = 360f * animatedPercent, useCenter = false,
                    topLeft = Offset(outerInset, outerInset), size = outerArcSize, style = outerStroke,
                )
            } else {
                rotate(orbitAngle) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, AeonPlayer.Sky300, Color.Transparent)),
                        startAngle = 0f, sweepAngle = 140f, useCenter = false,
                        topLeft = Offset(outerInset, outerInset), size = outerArcSize, style = outerStroke,
                    )
                }
            }

            // Inner decorative orbit — always spinning, gives the "alive/
            // futuristic" feel independent of how far along real progress is.
            rotate(-sweepAngle) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, Color(0xFF8C4DFF).copy(0.85f))),
                    startAngle = 0f, sweepAngle = 70f, useCenter = false,
                    topLeft = Offset(innerInset, innerInset), size = innerArcSize, style = innerStroke,
                )
            }
        }

        if (percent != null) {
            Text(
                "$percent%",
                color = Color.White,
                fontSize = (size.value * 0.20f).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
            )
        } else {
            Box(
                Modifier
                    .size(size * 0.16f)
                    .alpha(pulse)
                    .background(AeonPlayer.Sky300, CircleShape)
            )
        }
    }
}

// Compact "connection health" row — icon-led, unified instead of two
// disconnected pieces of colored text.
@Composable
private fun ConnectionStatRow(downloadSpeed: String, seeds: Int, compact: Boolean = false) {
    val fontSize = if (compact) 11.sp else 13.sp
    val iconSize = if (compact) 12.dp else 14.dp
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ArrowDownward, null, tint = AeonPlayer.Amber, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(4.dp))
            Text(downloadSpeed, color = Color.White.copy(0.8f), fontSize = fontSize, fontWeight = FontWeight.Medium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.People, null, tint = AeonPlayer.Green, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(4.dp))
            Text("$seeds", color = Color.White.copy(0.8f), fontSize = fontSize, fontWeight = FontWeight.Medium)
        }
    }
}


// ═══════════════════════════════════════════════════════════════════
//  Subtitle Style Page
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun SubtitleStylePage(style: SubtitleStyle, onChange: (SubtitleStyle) -> Unit) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(AeonPlayer.GlassFill, RoundedCornerShape(12.dp))
                .border(1.dp, AeonPlayer.GlassBorder, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Sample Subtitle Text", color = style.textColor, fontSize = style.fontSize.sp, fontWeight = style.fontWeight, textAlign = TextAlign.Center,
                modifier = Modifier.background(if (style.showBackground) style.backgroundColor else Color.Transparent, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("COLOR PRESET", color = AeonPlayer.Slate500, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SUBTITLE_COLOR_PRESETS) { preset ->
                val sel = style.textColor == preset.text && style.backgroundColor == preset.bg
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (sel) AeonPlayer.Sky300.copy(0.15f) else Color.White.copy(0.05f))
                        .border(if (sel) 1.5.dp else 0.dp, if (sel) AeonPlayer.Sky300 else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { onChange(style.copy(textColor = preset.text, backgroundColor = preset.bg, showBackground = preset.bg != Color.Transparent)) }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Box(Modifier.size(32.dp, 18.dp).background(if (preset.bg == Color.Transparent) Color.White.copy(0.1f) else preset.bg, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) { Text("A", color = preset.text, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(4.dp))
                    Text(preset.name, color = if (sel) AeonPlayer.Sky300 else AeonPlayer.Slate500, fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Font Size", color = Color.White, fontSize = 14.sp)
            Text("${style.fontSize}sp", color = AeonPlayer.Sky300, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value = style.fontSize.toFloat(), onValueChange = { onChange(style.copy(fontSize = it.toInt())) }, valueRange = 12f..36f, colors = SliderDefaults.colors(thumbColor = AeonPlayer.Sky300, activeTrackColor = AeonPlayer.Sky300))
        Row(Modifier.fillMaxWidth().clickable { onChange(style.copy(fontWeight = if (style.fontWeight == FontWeight.Bold) FontWeight.Normal else FontWeight.Bold)) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Bold Text", color = Color.White, fontSize = 14.sp)
            Switch(checked = style.fontWeight == FontWeight.Bold, onCheckedChange = { on -> onChange(style.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)) }, colors = SwitchDefaults.colors(checkedThumbColor = AeonPlayer.Sky300, checkedTrackColor = AeonPlayer.Sky300.copy(0.4f)))
        }
        Row(Modifier.fillMaxWidth().clickable { onChange(style.copy(showBackground = !style.showBackground)) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Background Box", color = Color.White, fontSize = 14.sp)
            Switch(checked = style.showBackground, onCheckedChange = { onChange(style.copy(showBackground = it)) }, colors = SwitchDefaults.colors(checkedThumbColor = AeonPlayer.Sky300, checkedTrackColor = AeonPlayer.Sky300.copy(0.4f)))
        }
        Spacer(Modifier.height(20.dp))
        Text("POSITION", color = AeonPlayer.Slate500, fontSize = 10.sp, letterSpacing = 1.sp); Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitlePosition.entries.forEach { pos ->
                val sel = style.position == pos
                Box(Modifier.weight(1f).background(if (sel) AeonPlayer.Sky300.copy(0.18f) else Color.White.copy(0.07f), RoundedCornerShape(10.dp)).border(if (sel) 1.5.dp else 0.dp, if (sel) AeonPlayer.Sky300 else Color.Transparent, RoundedCornerShape(10.dp)).clickable { onChange(style.copy(position = pos)) }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val icon = when (pos) { SubtitlePosition.TOP -> Icons.Rounded.VerticalAlignTop; SubtitlePosition.CENTER -> Icons.Rounded.VerticalAlignCenter; SubtitlePosition.BOTTOM -> Icons.Rounded.VerticalAlignBottom }
                        Icon(icon, null, tint = if (sel) AeonPlayer.Sky300 else AeonPlayer.Slate500, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(pos.name.lowercase().replaceFirstChar { it.uppercase() }, color = if (sel) AeonPlayer.Sky300 else AeonPlayer.Slate500, fontSize = 11.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        TextButton(onClick = { onChange(SubtitleStyle()); applySubtitleStyleToMpv(SubtitleStyle()) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Refresh, null, tint = AeonPlayer.Slate500, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Reset to Default", color = AeonPlayer.Slate500, fontSize = 13.sp)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Live Caption composables
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun LiveCaptionSettingsPage(isEnabled: Boolean, onRefresh: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(72.dp).background(if (isEnabled) AeonPlayer.Sky300.copy(0.15f) else Color.White.copy(0.08f), CircleShape).border(2.dp, if (isEnabled) AeonPlayer.Sky300 else AeonPlayer.Slate500, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.ClosedCaption, null, tint = if (isEnabled) AeonPlayer.Sky300 else AeonPlayer.Slate500, modifier = Modifier.size(36.dp)) }
        Spacer(Modifier.height(12.dp))
        Text(if (isEnabled) "Live Caption is ON" else "Live Caption is OFF", color = if (isEnabled) AeonPlayer.Sky300 else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Google's on-device AI captions work for\nall media including your movies.", color = AeonPlayer.Slate500, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
        Spacer(Modifier.height(20.dp))
        if (!isEnabled) {
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AeonPlayer.Sky300)) {
                Icon(Icons.Rounded.OpenInNew, null, tint = Color.Black, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Enable Live Caption", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        } else {
            Box(Modifier.fillMaxWidth().background(AeonPlayer.Green.copy(0.12f), RoundedCornerShape(10.dp)).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = AeonPlayer.Green, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                    Text("Active — captions appear automatically.", color = AeonPlayer.Green, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Settings, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Manage Caption Settings") }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, null, tint = AeonPlayer.Slate500, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Refresh Status", color = AeonPlayer.Slate500, fontSize = 12.sp) }
    }
}

@Composable
private fun LiveCaptionBanner(onEnable: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier
            .background(AeonPlayer.GlassFill, RoundedCornerShape(12.dp))
            .border(1.dp, AeonPlayer.GlassBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ClosedCaption, null, tint = AeonPlayer.Sky300, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Enable Live Caption for auto subtitles", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onEnable, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("Enable", color = AeonPlayer.Sky300, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, null, tint = AeonPlayer.Slate500, modifier = Modifier.size(16.dp)) }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Shared small composables
// ═══════════════════════════════════════════════════════════════════
@Composable
fun SettingsItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).background(Color.White.copy(0.06f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = AeonPlayer.Sky300, modifier = Modifier.size(19.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = AeonPlayer.TextTitle, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color.White.copy(0.5f), fontSize = AeonPlayer.TextBody)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(20.dp))
    }
}

// Small caps section header used to group the settings list (Playback /
// Subtitles & Audio / Support) instead of one long undifferentiated list.
@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = AeonPlayer.Slate500,
        fontSize = AeonPlayer.TextCaption,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp, start = 6.dp),
    )
}

@Composable
private fun SubTrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked, null, tint = if (selected) AeonPlayer.Sky300 else AeonPlayer.Slate500, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (selected) AeonPlayer.Sky300 else Color.White, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun DecodeInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AeonPlayer.Slate500, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
