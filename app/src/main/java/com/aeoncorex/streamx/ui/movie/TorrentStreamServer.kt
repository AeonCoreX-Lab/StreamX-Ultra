package com.aeoncorex.streamx.ui.movie

import android.util.Log
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

// ═══════════════════════════════════════════════════════════════════
//  TorrentStreamServer — Local HTTP server for MPV (Ktor CIO)
//  ──────────────────────────────────────────────────────────────────
//  NanoHTTPD REMOVED: abandoned since 2019, last release 2.3.1,
//  no Kotlin coroutine support, active CVEs in older versions.
//
//  REPLACEMENT: Ktor CIO (io.ktor:ktor-server-cio)
//    • Actively maintained by JetBrains
//    • Pure Kotlin + coroutines — no legacy Java thread pool
//    • Lightweight (no Netty/servlet overhead on Android)
//    • Same Range-request support MPV requires for seeking
//
//  Endpoints:
//    GET /stream           → serves the torrent video file with
//                            Range support for MPV seeking
//    GET /sub/{filename}   → serves subtitle files from the same
//                            directory (used by StreamXCore.addExternalSubtitle)
//
//  Usage (unchanged from NanoHTTPD version):
//    val url = TorrentStreamServer.start(File("/path/to/video.mkv"))
//    // url == "http://127.0.0.1:8088/stream"
//    StreamXCore.playMpvVideo(url)
//    TorrentStreamServer.stop()   // player শেষ হলে
// ═══════════════════════════════════════════════════════════════════

class TorrentStreamServer private constructor(
    private val videoFile: File,
    private val port: Int
) {

    companion object {
        private const val TAG  = "TorrentStreamServer"
        private const val PORT = 8088

        @Volatile private var instance: TorrentStreamServer? = null

        /** Start (or restart) the HTTP server for [file]. Returns the MPV URL. */
        fun start(file: File): String {
            instance?.stopInternal()
            val server = TorrentStreamServer(file, PORT)
            server.startInternal()
            instance = server
            Log.d(TAG, "Ktor stream server started → port $PORT for: ${file.name}")
            return "http://127.0.0.1:$PORT/stream"
        }

        fun stop() {
            instance?.stopInternal()
            instance = null
            Log.d(TAG, "Ktor stream server stopped")
        }

        /**
         * Update the file being served.
         * Called while the torrent is still downloading (path becomes known early).
         */
        fun updateFile(file: File) {
            instance?.currentFile = file
            Log.d(TAG, "Updated stream file: ${file.name}")
        }

        /**
         * Returns a subtitle URL for a file in the same directory as the video.
         * Example: TorrentStreamServer.subtitleUrl("Movie.en.srt")
         * → "http://127.0.0.1:8088/sub/Movie.en.srt"
         *
         * Call StreamXCore.addExternalSubtitle(url) with this URL to load it into MPV.
         */
        fun subtitleUrl(filename: String): String =
            "http://127.0.0.1:$PORT/sub/$filename"
    }

    // @Volatile ensures cross-thread visibility: TorrentEngine (IO thread)
    // writes this while Ktor CIO (network thread) reads it.
    @Volatile var currentFile: File = videoFile

    // ─── FIX: Ktor 3.x ───────────────────────────────────────────────
    // `embeddedServer(...).start(wait = false)` returns EmbeddedServer<*, *>
    // NOT ApplicationEngine anymore. Use star-projection for engine type.
    // ─────────────────────────────────────────────────────────────────
    private var engine: EmbeddedServer<*, *>? = null

    private fun startInternal() {
        engine = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {

                // ── Video stream — Range-aware ──────────────────
                get("/stream") {
                    serveRangeFile(call, currentFile)
                }

                // ── Subtitle files — same directory as video ────
                // MPV receives: "sub-add http://127.0.0.1:8088/sub/Movie.srt select"
                get("/sub/{filename}") {
                    val filename = call.parameters["filename"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)

                    val subFile = File(currentFile.parentFile, filename)
                    if (!subFile.exists() || !subFile.isFile) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    // Content-Type for subtitles — MPV reads by content, not MIME
                    val mime = when {
                        filename.endsWith(".srt",  ignoreCase = true) -> "application/x-subrip"
                        filename.endsWith(".ass",  ignoreCase = true) -> "text/x-ssa"
                        filename.endsWith(".ssa",  ignoreCase = true) -> "text/x-ssa"
                        filename.endsWith(".vtt",  ignoreCase = true) -> "text/vtt"
                        else                                           -> "text/plain"
                    }
                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    call.respond(LocalFileContent(subFile, ContentType.parse(mime)))
                }
            }
        }.start(wait = false)
    }

    private fun stopInternal() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        engine = null
    }
}

// ──────────────────────────────────────────────────────────────────
//  serveRangeFile — handles both HEAD/full GET and Range requests.
//
//  MPV's HTTP demuxer always sends a Range: bytes=0- first to probe
//  the file size, then sends per-seek Range requests. Both need
//  Content-Range + 206 Partial Content responses.
// ──────────────────────────────────────────────────────────────────
private suspend fun serveRangeFile(call: ApplicationCall, file: File) {
    if (!file.exists() || file.length() == 0L) {
        Log.w("TorrentStreamServer", "File not ready: ${file.absolutePath}")
        call.respond(HttpStatusCode.ServiceUnavailable, "File not ready yet")
        return
    }

    // Snapshot file length once — file grows as torrent downloads,
    // but we report the final expected size (stored in totalSize) so
    // MPV knows the seek range. Actual bytes served are clamped to available.
    val fileLength  = file.length()
    val contentType = ContentType.parse(mimeForFile(file.name))
    val rangeHeader = call.request.header(HttpHeaders.Range)

    if (rangeHeader != null && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
        // ── Range request (MPV seek) ─────────────────────────
        val rangeSpec    = rangeHeader.removePrefix("bytes=").trim()
        val dash         = rangeSpec.indexOf('-')
        val start        = if (dash > 0) rangeSpec.substring(0, dash).toLongOrNull() ?: 0L else 0L
        val requestedEnd = if (dash < rangeSpec.length - 1)
            rangeSpec.substring(dash + 1).toLongOrNull()
        else null
        val end = minOf(requestedEnd ?: (fileLength - 1), fileLength - 1)

        // Clamp end to bytes actually available on disk
        val available = minOf(end, file.length() - 1)
        if (available < start) {
            call.response.header(HttpHeaders.ContentRange, "bytes */$fileLength")
            call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
            return
        }

        val contentLength = available - start + 1

        call.response.header(HttpHeaders.ContentRange,  "bytes $start-$available/$fileLength")
        call.response.header(HttpHeaders.AcceptRanges,  "bytes")
        call.response.header(HttpHeaders.CacheControl,  "no-cache")

        call.respondOutputStream(
            contentType   = contentType,
            status        = HttpStatusCode.PartialContent,
            contentLength = contentLength
        ) {
            val raf = RandomAccessFile(file, "r")
            try {
                raf.seek(start)
                val buf = ByteArray(65_536)
                val fis = FileInputStream(raf.fd)
                var remaining = contentLength
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n      = fis.read(buf, 0, toRead)
                    if (n <= 0) break
                    write(buf, 0, n)
                    remaining -= n
                }
                flush()
            } finally {
                raf.close()
            }
        }

    } else {
        // ── Full file request (initial HEAD or plain GET) ────
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respond(LocalFileContent(file, contentType))
    }
}

private fun mimeForFile(name: String): String = when {
    name.endsWith(".mkv",  ignoreCase = true) -> "video/x-matroska"
    name.endsWith(".mp4",  ignoreCase = true) -> "video/mp4"
    name.endsWith(".avi",  ignoreCase = true) -> "video/x-msvideo"
    name.endsWith(".mov",  ignoreCase = true) -> "video/quicktime"
    name.endsWith(".webm", ignoreCase = true) -> "video/webm"
    name.endsWith(".ts",   ignoreCase = true) -> "video/mp2t"
    else                                       -> "video/mp4"
}
