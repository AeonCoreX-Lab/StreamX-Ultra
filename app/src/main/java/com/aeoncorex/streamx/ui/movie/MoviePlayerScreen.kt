package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.Keep
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

fun autoSubtitle(title: String, context: Context, onStatus: (String) -> Unit) {
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
                fetchSubtitle(title, lang.code, context) { msg -> onStatus(msg) }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
//  fetchSubtitle — FIXED
//
//  OLD API (broken since 2024):
//    rest.opensubtitles.org/search/query-{title}/sublanguageid-{lang}
//    → HTTP 410 Gone. OpenSubtitles shut down REST v1 in late 2023.
//    → All subtitle requests silently failed with "Server error: 401"
//      or "Error: …Connection refused".
//
//  NEW API: Stremio OpenSubtitles proxy (no API key, no rate limit)
//    opensubtitles-v3.strem.io/subtitles/{imdbId}.json  (by IMDB ID)
//    opensubtitles-v3.strem.io/subtitles/search={title}.json (by title)
//    SubtitleRepository.search() handles both modes and returns ranked
//    results with direct .srt download URLs.
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

private fun fetchSubtitle(title: String, langCode: String, context: Context, onComplete: (String) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val lang2   = ISO639_3TO2[langCode] ?: langCode.take(2)
            val results = SubtitleRepository.search(
                imdbId   = null,
                title    = title,
                type     = MovieType.MOVIE,
                langCode = lang2
            )
            withContext(Dispatchers.Main) {
                val best = results.firstOrNull()
                if (best != null) {
                    // Direct .srt URL — MPV downloads and renders it
                    try { StreamXCore.addExternalSubtitle(best.url) } catch (e: Exception) {
                        Log.e("SubtitleFetch", "sub-add failed: ${e.message}")
                    }
                    onComplete("✓ Subtitle loaded!")
                } else {
                    onComplete("No subtitle found for this language")
                }
            }
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
fun MoviePlayerScreen(navController: NavController, encodedUrl: String) {
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
    var surfaceW           by remember { mutableIntStateOf(0) }
    var surfaceH           by remember { mutableIntStateOf(0) }
    var isControlsVisible  by remember { mutableStateOf(true) }
    var isLocked           by remember { mutableStateOf(false) }
    var showSettingsMenu   by remember { mutableStateOf(false) }
    var activeSettingPage  by remember { mutableStateOf("Main") }
    var selectedQuality    by remember { mutableStateOf(GPU_QUALITY_PRESETS[0]) }
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
            TorrentEngine.stop()
            TorrentEngine.clearCache(context)
        }
    }

    // ── Torrent / direct URL ──────────────────────────────────────
    LaunchedEffect(decodedUrl) {
        var retryCount = 0; val maxRetries = 3
        while (retryCount < maxRetries) {
            if (decodedUrl.startsWith("magnet:?")) {
                withContext(Dispatchers.IO) { TorrentEngine.clearCache(context) }
                val saveDir = withContext(Dispatchers.IO) {
                    context.getExternalFilesDir("torrents")?.absolutePath
                        ?: context.cacheDir.absolutePath
                }
                var metadataTimeout = 0; var completed = false
                TorrentEngine.start(decodedUrl, saveDir)
                TorrentEngine.status.collect { status ->
                    when (status.state) {
                        TorrentEngine.State.METADATA -> {
                            statusMsg = "Fetching metadata…"
                            if (++metadataTimeout > 240) {
                                statusMsg = if (retryCount < maxRetries - 1) "Timeout – retrying (${retryCount+1}/$maxRetries)" else "Timeout – last attempt"
                                TorrentEngine.stop(); completed = true
                            }
                        }
                        TorrentEngine.State.BUFFERING -> { isPreBuffering = true; torrentProgress = status.progress; statusMsg = "Buffering ${status.progress}%"; downloadSpeed = "${status.speedBps / 1000} KB/s"; seeds = status.seeds; metadataTimeout = 0 }
                        TorrentEngine.State.READY  -> {
                            // Do NOT set isPreBuffering=false here.
                            // The overlay stays until time-sync confirms MPV
                            // has loaded (duration>0) for the current videoPath.
                            videoPath = status.streamUrl
                            statusMsg = "Opening video…"
                            completed = true
                        }
                        TorrentEngine.State.ERROR  -> { statusMsg = "Error: Torrent engine failed"; isPreBuffering = false; completed = true }
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

        autoSubtitle(movieTitle.ifBlank { "Movie" }, context) { msg ->
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

                        withContext(Dispatchers.Main) {
                            if (t >= 0.0) currentTime = t
                            if (d > 0.0)  totalDuration = d
                            decodeModeLabel = decMode

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
            Box(Modifier.align(Alignment.Center).background(Color.Black.copy(0.7f), RoundedCornerShape(16.dp)).padding(24.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    gestureIcon?.let { Icon(it, null, tint = Color.Cyan, modifier = Modifier.size(48.dp)) }
                    Text(gestureText, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Double-tap anim
        if (rewindAnimAlpha  > 0) Box(Modifier.align(Alignment.CenterStart).padding(50.dp).alpha(rewindAnimAlpha).background(Color.Black.copy(0.5f), CircleShape).padding(16.dp)) { Icon(Icons.Rounded.FastRewind,  null, tint = Color.White, modifier = Modifier.size(40.dp)) }
        if (forwardAnimAlpha > 0) Box(Modifier.align(Alignment.CenterEnd).padding(50.dp).alpha(forwardAnimAlpha).background(Color.Black.copy(0.5f), CircleShape).padding(16.dp)) { Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(40.dp)) }

        // Pre-buffer overlay
        if (isPreBuffering) {  // stays until mpvPath==videoPath && duration>0
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    CircularProgressIndicator(color = Color.Cyan, strokeWidth = 3.dp)
                    Spacer(Modifier.height(20.dp))
                    Text(statusMsg, color = Color.White, textAlign = TextAlign.Center, fontSize = 14.sp)
                    if (torrentProgress > 0) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { torrentProgress / 100f }, modifier = Modifier.fillMaxWidth(0.7f).height(4.dp), color = Color.Cyan, trackColor = Color.White.copy(0.2f))
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text("▼ $downloadSpeed", color = Color.Green,     fontSize = 13.sp)
                            Text("S: $seeds",        color = Color.LightGray, fontSize = 13.sp)
                        }
                    }
                }
                IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            }
        }

        // Mid-play buffering overlay
        if (!isPreBuffering && isMidBuffering && videoPath != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.background(Color.Black.copy(0.78f), RoundedCornerShape(16.dp)).padding(horizontal = 32.dp, vertical = 22.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.height(10.dp))
                        Text("Buffering $cachePercent%", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("▼ $downloadSpeed   S: $seeds", color = Color.Green, fontSize = 12.sp)
                    }
                }
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
                CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
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
                    Row(Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopStart), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (decodedUrl.startsWith("magnet")) {
                                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 12.dp)) {
                                    Text("▼ $downloadSpeed", color = Color.Green,     fontSize = 12.sp)
                                    Text("S: $seeds",        color = Color.LightGray, fontSize = 10.sp)
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
                                        tint     = if (liveCaptionEnabled) Color.Cyan else Color.White,
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
                                    tint     = if (isSubtitleEnabled) Color.Cyan else Color.White.copy(0.5f),
                                    modifier = Modifier.size(28.dp))
                            }

                            IconButton(onClick = { showSettingsMenu = true; activeSettingPage = "Main" }) {
                                Icon(Icons.Rounded.Settings, null, tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                    }

                    // Center controls
                    if (!isLocked) {
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                            IconButton(onClick = { val t = max(0.0, currentTime - 10.0); try { StreamXCore.seekMpvAbsolute(t); currentTime = t } catch (e: Exception) {} }) { Icon(Icons.Rounded.Replay10,   null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                            IconButton(onClick = { isPlaying = !isPlaying; try { StreamXCore.pauseMpvVideo(!isPlaying) } catch (e: Exception) {} }) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(64.dp)) }
                            IconButton(onClick = { val t = min(totalDuration, currentTime + 10.0); try { StreamXCore.seekMpvAbsolute(t); currentTime = t } catch (e: Exception) {} }) { Icon(Icons.Rounded.Forward10, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                        }
                    }

                    // Lock
                    IconButton(onClick = { isLocked = !isLocked }, modifier = Modifier.align(Alignment.CenterEnd).padding(32.dp)) {
                        Icon(if (isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = if (isLocked) Color.Red else Color.White)
                    }

                    // Seek bar
                    if (!isLocked) {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                            val displayTime = if (isSeeking) seekPreviewTime else currentTime
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTime(displayTime.toLong()),   color = Color.White, fontSize = 12.sp)
                                Text(formatTime(totalDuration.toLong()), color = Color.White, fontSize = 12.sp)
                            }
                            Slider(
                                value             = displayTime.toFloat(),
                                onValueChange     = { v -> isSeeking = true; seekPreviewTime = v.toDouble().coerceIn(0.0, totalDuration) },
                                onValueChangeFinished = {
                                    val target = seekPreviewTime.coerceIn(0.0, totalDuration)
                                    try { StreamXCore.seekMpvAbsolute(target); currentTime = target } catch (e: Exception) {}
                                    isSeeking = false
                                },
                                valueRange = 0f..max(1f, totalDuration.toFloat()),
                                colors     = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan)
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
                Box(Modifier.fillMaxHeight().width(340.dp).background(Color(0xF01A1A2E)).padding(16.dp)) {
                    Column {
                        // Header
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                            if (activeSettingPage != "Main") {
                                IconButton(onClick = { activeSettingPage = "Main" }, modifier = Modifier.size(24.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
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
                            }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }

                        LazyColumn {
                            item {
                                when (activeSettingPage) {
                                    "Main" -> {
                                        SettingsItem(Icons.Rounded.HighQuality,   "Video Quality",   selectedQuality.label)                               { activeSettingPage = "Quality" }
                                        SettingsItem(Icons.Rounded.Subtitles,     "Subtitles",       "Tracks & Download")                                  { subTracks = StreamXCore.getTrackList("sub"); activeSettingPage = "Subtitles" }
                                        SettingsItem(Icons.Rounded.Translate,     "Sub Language",    selectedSubLang.flag + " " + selectedSubLang.label)   { activeSettingPage = "SubLanguage" }
                                        SettingsItem(Icons.Rounded.FormatSize,    "Subtitle Style",  "Color, size, font")                                  { activeSettingPage = "SubStyle" }
                                        SettingsItem(Icons.Rounded.LibraryMusic,  "Audio Track",     "Internal tracks")                                    { activeSettingPage = "Audio" }
                                        // Live-updating decode mode — refreshed every 250ms by the
                                        // time-sync poll loop (decodeModeLabel state var). Tapping
                                        // opens a detail page with codec/pixel-format diagnostics.
                                        SettingsItem(Icons.Rounded.Memory,        "Decode Mode",     decodeModeLabel)                                      { activeSettingPage = "DecodeInfo" }
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                            SettingsItem(Icons.Rounded.ClosedCaption, "Live Caption", if (liveCaptionEnabled) "Enabled ✓" else "Tap to enable") { activeSettingPage = "LiveCaption" }
                                    }
                                    "Quality" -> {
                                        Text("GPU Render Quality", color = Color.Gray, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
                                        GPU_QUALITY_PRESETS.forEach { preset ->
                                            val sel = selectedQuality.label == preset.label
                                            Row(Modifier.fillMaxWidth().background(if (sel) Color.Cyan.copy(0.12f) else Color.Transparent, RoundedCornerShape(10.dp)).clickable { selectedQuality = preset; applyQualityPreset(preset); showSettingsMenu = false }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(preset.label,    color = if (sel) Color.Cyan else Color.White, fontSize = 15.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                                    Text(preset.subtitle, color = Color.Gray, fontSize = 11.sp)
                                                }
                                                if (sel) Icon(Icons.Rounded.Check, null, tint = Color.Cyan, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                    "Subtitles" -> {
                                        Button(onClick = {
                                            if (!isSearchingSub) {
                                                isSearchingSub = true; subSearchMsg = "Searching…"
                                                fetchSubtitle(movieTitle.ifBlank { "Movie" }, selectedSubLang.code, context) { msg ->
                                                    isSearchingSub = false; subSearchMsg = msg; subTracks = StreamXCore.getTrackList("sub")
                                                }
                                            }
                                        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F))) {
                                            if (isSearchingSub) { CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Searching…", color = Color.White, fontSize = 12.sp) }
                                            else { Icon(Icons.Rounded.Download, null, tint = Color.Cyan, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Download  ${selectedSubLang.flag} ${selectedSubLang.label}", color = Color.White) }
                                        }
                                        if (subSearchMsg.isNotEmpty()) Text(subSearchMsg, color = Color.Cyan, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                                        Spacer(Modifier.height(14.dp)); HorizontalDivider(color = Color.White.copy(0.1f)); Spacer(Modifier.height(10.dp))
                                        Text("SUBTITLE TRACKS", color = Color.Gray, fontSize = 10.sp, letterSpacing = 1.sp); Spacer(Modifier.height(8.dp))
                                        SubTrackRow("Disable", false) { StreamXCore.setSubTrack(-1); showSettingsMenu = false }
                                        if (subTracks.isEmpty()) Text("No tracks found", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                                        else subTracks.forEach { t -> SubTrackRow(t.title, t.selected) { StreamXCore.setSubTrack(t.id); showSettingsMenu = false } }
                                    }
                                    "SubLanguage" -> {
                                        Text("SELECT LANGUAGE", color = Color.Gray, fontSize = 10.sp, letterSpacing = 1.sp)
                                        Text("Language for auto-download", color = Color.Gray.copy(0.7f), fontSize = 11.sp)
                                        Spacer(Modifier.height(12.dp))
                                        SUBTITLE_LANGUAGES.forEach { lang ->
                                            val sel = selectedSubLang.code == lang.code
                                            Row(Modifier.fillMaxWidth().background(if (sel) Color.Cyan.copy(0.12f) else Color.Transparent, RoundedCornerShape(10.dp)).clickable {
                                                selectedSubLang = lang; saveSubLang(context, lang)
                                                isSearchingSub = true; subSearchMsg = "Loading ${lang.flag} ${lang.label}…"
                                                fetchSubtitle(movieTitle.ifBlank { "Movie" }, lang.code, context) { msg -> isSearchingSub = false; subSearchMsg = msg; subTracks = StreamXCore.getTrackList("sub") }
                                                activeSettingPage = "Subtitles"
                                            }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(lang.flag, fontSize = 22.sp)
                                                Spacer(Modifier.width(12.dp))
                                                Text(lang.label, color = if (sel) Color.Cyan else Color.White, fontSize = 15.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                                if (sel) Icon(Icons.Rounded.Check, null, tint = Color.Cyan, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                    "SubStyle" -> { SubtitleStylePage(style = subtitleStyle, onChange = { s -> subtitleStyle = s; applySubtitleStyleToMpv(s) }) }
                                    "Audio" -> {
                                        val audioTracks = remember(activeSettingPage) { StreamXCore.getTrackList("audio") }
                                        Text("AUDIO TRACKS", color = Color.Gray, fontSize = 10.sp, letterSpacing = 1.sp); Spacer(Modifier.height(8.dp))
                                        if (audioTracks.isEmpty()) Text("No audio tracks found", color = Color.Gray, fontSize = 12.sp)
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
                                                tint = if (isHardware) Color(0xFF4CAF50) else Color(0xFFFFA726),
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column {
                                                Text(decodeModeLabel, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                                Text(
                                                    if (isHardware) "Hardware-accelerated decoding" else "Software (CPU) decoding",
                                                    color = Color.Gray, fontSize = 11.sp
                                                )
                                            }
                                        }

                                        if (autoSwitched) {
                                            val reasonText = when (reason) {
                                                "10bit"        -> "this file uses a 10-bit color format that your device's hardware decoder can't render correctly (would show a black screen)."
                                                "oversized"    -> "this file's resolution exceeds what your device's hardware decoder reliably supports."
                                                "black-frame"  -> "your device's hardware decoder produced a blank frame for this file \u2014 confirmed by checking the actual picture."
                                                "log-detected" -> "your device's hardware decoder reported an error while starting this file."
                                                "manual"       -> "you've enabled \u201cAlways use software decoding\u201d below."
                                                else           -> "a hardware decoding issue was detected."
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0xFFFFA726).copy(0.12f), RoundedCornerShape(10.dp))
                                                    .padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Rounded.Info, null, tint = Color(0xFFFFA726), modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    "Auto-switched to software decoding \u2014 $reasonText",
                                                    color = Color(0xFFFFA726), fontSize = 11.sp, lineHeight = 15.sp
                                                )
                                            }
                                            Spacer(Modifier.height(14.dp))
                                        }

                                        HorizontalDivider(color = Color.White.copy(0.1f))
                                        Spacer(Modifier.height(10.dp))
                                        Text("DIAGNOSTICS", color = Color.Gray, fontSize = 10.sp, letterSpacing = 1.sp)
                                        Spacer(Modifier.height(8.dp))
                                        DecodeInfoRow("Codec",          codec)
                                        DecodeInfoRow("Pixel Format",   pixfmt)
                                        DecodeInfoRow("HW Decoder",     hwdecCurrent.ifEmpty { "Not active (software)" })
                                        DecodeInfoRow("Auto-switched",  if (autoSwitched) "Yes" else "No")
                                        DecodeInfoRow("GPU Rendering",  gpuContext)

                                        Spacer(Modifier.height(20.dp))
                                        HorizontalDivider(color = Color.White.copy(0.1f))
                                        Spacer(Modifier.height(14.dp))

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
                                                    color = Color.Gray, fontSize = 11.sp, lineHeight = 14.sp,
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
                                                colors = SwitchDefaults.colors(checkedThumbColor = Color.Cyan, checkedTrackColor = Color.Cyan.copy(0.5f))
                                            )
                                        }

                                        Spacer(Modifier.height(16.dp))
                                        Text(
                                            "StreamX automatically detects video formats that render as a " +
                                            "black screen on hardware decoders (10-bit HDR content, oversized " +
                                            "frames, decoder errors, or by checking the actual picture) and " +
                                            "switches to software decoding for that file only \u2014 no settings " +
                                            "needed in most cases.",
                                            color = Color.Gray.copy(0.8f), fontSize = 11.sp, lineHeight = 16.sp
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
//  Subtitle Style Page
// ═══════════════════════════════════════════════════════════════════
@Composable
private fun SubtitleStylePage(style: SubtitleStyle, onChange: (SubtitleStyle) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(72.dp).background(Color(0xFF0D0D1A), RoundedCornerShape(12.dp)).border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Text("Sample Subtitle Text", color = style.textColor, fontSize = style.fontSize.sp, fontWeight = style.fontWeight, textAlign = TextAlign.Center,
                modifier = Modifier.background(if (style.showBackground) style.backgroundColor else Color.Transparent, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("COLOR PRESET", color = Color.Gray, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SUBTITLE_COLOR_PRESETS) { preset ->
                val sel = style.textColor == preset.text && style.backgroundColor == preset.bg
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (sel) Color.Cyan.copy(0.15f) else Color.White.copy(0.05f))
                        .border(if (sel) 1.5.dp else 0.dp, if (sel) Color.Cyan else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { onChange(style.copy(textColor = preset.text, backgroundColor = preset.bg, showBackground = preset.bg != Color.Transparent)) }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Box(Modifier.size(32.dp, 18.dp).background(if (preset.bg == Color.Transparent) Color.White.copy(0.1f) else preset.bg, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) { Text("A", color = preset.text, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(4.dp))
                    Text(preset.name, color = if (sel) Color.Cyan else Color.Gray, fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp)); HorizontalDivider(color = Color.White.copy(0.08f)); Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Font Size", color = Color.White, fontSize = 14.sp)
            Text("${style.fontSize}sp", color = Color.Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value = style.fontSize.toFloat(), onValueChange = { onChange(style.copy(fontSize = it.toInt())) }, valueRange = 12f..36f, colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan))
        Row(Modifier.fillMaxWidth().clickable { onChange(style.copy(fontWeight = if (style.fontWeight == FontWeight.Bold) FontWeight.Normal else FontWeight.Bold)) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Bold Text", color = Color.White, fontSize = 14.sp)
            Switch(checked = style.fontWeight == FontWeight.Bold, onCheckedChange = { on -> onChange(style.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)) }, colors = SwitchDefaults.colors(checkedThumbColor = Color.Cyan, checkedTrackColor = Color.Cyan.copy(0.4f)))
        }
        Row(Modifier.fillMaxWidth().clickable { onChange(style.copy(showBackground = !style.showBackground)) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Background Box", color = Color.White, fontSize = 14.sp)
            Switch(checked = style.showBackground, onCheckedChange = { onChange(style.copy(showBackground = it)) }, colors = SwitchDefaults.colors(checkedThumbColor = Color.Cyan, checkedTrackColor = Color.Cyan.copy(0.4f)))
        }
        Spacer(Modifier.height(4.dp)); HorizontalDivider(color = Color.White.copy(0.08f)); Spacer(Modifier.height(14.dp))
        Text("POSITION", color = Color.Gray, fontSize = 10.sp, letterSpacing = 1.sp); Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitlePosition.entries.forEach { pos ->
                val sel = style.position == pos
                Box(Modifier.weight(1f).background(if (sel) Color.Cyan.copy(0.18f) else Color.White.copy(0.07f), RoundedCornerShape(10.dp)).border(if (sel) 1.5.dp else 0.dp, if (sel) Color.Cyan else Color.Transparent, RoundedCornerShape(10.dp)).clickable { onChange(style.copy(position = pos)) }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val icon = when (pos) { SubtitlePosition.TOP -> Icons.Rounded.VerticalAlignTop; SubtitlePosition.CENTER -> Icons.Rounded.VerticalAlignCenter; SubtitlePosition.BOTTOM -> Icons.Rounded.VerticalAlignBottom }
                        Icon(icon, null, tint = if (sel) Color.Cyan else Color.Gray, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(pos.name.lowercase().replaceFirstChar { it.uppercase() }, color = if (sel) Color.Cyan else Color.Gray, fontSize = 11.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = { onChange(SubtitleStyle()); applySubtitleStyleToMpv(SubtitleStyle()) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Refresh, null, tint = Color.Gray, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Reset to Default", color = Color.Gray, fontSize = 13.sp)
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
        Box(Modifier.size(72.dp).background(if (isEnabled) Color.Cyan.copy(0.15f) else Color.White.copy(0.08f), CircleShape).border(2.dp, if (isEnabled) Color.Cyan else Color.Gray, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.ClosedCaption, null, tint = if (isEnabled) Color.Cyan else Color.Gray, modifier = Modifier.size(36.dp)) }
        Spacer(Modifier.height(12.dp))
        Text(if (isEnabled) "Live Caption is ON" else "Live Caption is OFF", color = if (isEnabled) Color.Cyan else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Google's on-device AI captions work for\nall media including your movies.", color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
        Spacer(Modifier.height(20.dp))
        if (!isEnabled) {
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan)) {
                Icon(Icons.Rounded.OpenInNew, null, tint = Color.Black, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Enable Live Caption", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        } else {
            Box(Modifier.fillMaxWidth().background(Color(0xFF0D2B0D), RoundedCornerShape(10.dp)).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF66BB6A), modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                    Text("Active — captions appear automatically.", color = Color(0xFF66BB6A), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Settings, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Manage Caption Settings") }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, null, tint = Color.Gray, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Refresh Status", color = Color.Gray, fontSize = 12.sp) }
    }
}

@Composable
private fun LiveCaptionBanner(onEnable: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.background(Color(0xFF1A2A3A), RoundedCornerShape(12.dp)).border(1.dp, Color.Cyan.copy(0.3f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.ClosedCaption, null, tint = Color.Cyan, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Enable Live Caption for auto subtitles", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onEnable, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("Enable", color = Color.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, null, tint = Color.Gray, modifier = Modifier.size(16.dp)) }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Shared small composables
// ═══════════════════════════════════════════════════════════════════
@Composable
fun SettingsItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(title, color = Color.White, fontSize = 16.sp); Text(subtitle, color = Color.LightGray, fontSize = 12.sp) }
        Icon(Icons.Rounded.ChevronRight, null, tint = Color.Gray)
    }
}

@Composable
private fun SubTrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked, null, tint = if (selected) Color.Cyan else Color.Gray, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (selected) Color.Cyan else Color.White, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun DecodeInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
