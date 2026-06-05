package com.aeoncorex.streamx.streaming

import org.json.JSONArray
import org.json.JSONObject

// ═════════════════════════════════════════════════════════════════════════════
//  AddonDescriptor.kt  —  StreamX Addon Protocol Types
//
//  Mirrors Stremio-core's types exactly:
//    Descriptor  = manifest + transportUrl + flags
//    Manifest    = id, version, name, resources, types, idPrefixes, catalogs
//    Stream      = url/infoHash/ytId + name + description + subtitles + behaviorHints
//
//  Two addon kinds coexist:
//    • HTTP_ENDPOINT  — user pastes a URL ending in /manifest.json
//                       app calls /{resource}/{type}/{id}.json  (Stremio protocol)
//    • BUNDLE_REPO    — GitHub Pages repo with bundled JS files
//                       (current Vega-style system, kept for backward compat)
// ═════════════════════════════════════════════════════════════════════════════

enum class AddonKind { HTTP_ENDPOINT, BUNDLE_REPO }

// ── Descriptor ───────────────────────────────────────────────────────────────

data class AddonDescriptor(
    val manifest:     AddonManifest,
    val transportUrl: String,          // ends with /manifest.json  OR  bundle base URL
    val kind:         AddonKind        = AddonKind.HTTP_ENDPOINT,
    val flags:        AddonFlags       = AddonFlags()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("manifest",     manifest.toJson())
        put("transportUrl", transportUrl)
        put("kind",         kind.name)
        put("flags",        flags.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): AddonDescriptor = AddonDescriptor(
            manifest     = AddonManifest.fromJson(o.getJSONObject("manifest")),
            transportUrl = o.getString("transportUrl"),
            kind         = AddonKind.valueOf(o.optString("kind", "HTTP_ENDPOINT")),
            flags        = AddonFlags.fromJson(o.optJSONObject("flags") ?: JSONObject())
        )
    }
}

data class AddonFlags(
    val official:  Boolean = false,   // built-in / first-party
    val protected: Boolean = false,   // cannot be removed by user
    val verified:  Boolean = false,   // community-verified
    val nsfw:      Boolean = false
) {
    fun toJson() = JSONObject().apply {
        put("official", official); put("protected", protected)
        put("verified", verified); put("nsfw",       nsfw)
    }
    companion object {
        fun fromJson(o: JSONObject) = AddonFlags(
            official  = o.optBoolean("official",  false),
            protected = o.optBoolean("protected", false),
            verified  = o.optBoolean("verified",  false),
            nsfw      = o.optBoolean("nsfw",      false)
        )
    }
}

// ── Manifest ─────────────────────────────────────────────────────────────────

data class AddonManifest(
    val id:           String,
    val version:      String               = "1.0.0",
    val name:         String,
    val description:  String?              = null,
    val logo:         String?              = null,
    val background:   String?              = null,
    // Globally supported content types: "movie", "series", "channel", "tv"
    val types:        List<String>         = emptyList(),
    // Resources this addon provides: "stream", "catalog", "meta", "subtitles"
    val resources:    List<String>         = emptyList(),
    // ID prefixes this addon handles: ["tt"] = IMDB, ["tmdb:"] = TMDB
    val idPrefixes:   List<String>?        = null,
    val catalogs:     List<ManifestCatalog> = emptyList(),
    // contactEmail, behaviorHints etc. ignored for now
) {
    /** Does this addon provide streams for the given type + id? */
    fun supportsStream(type: String, id: String): Boolean {
        if ("stream" !in resources) return false
        if (types.isNotEmpty() && type !in types) return false
        if (!idPrefixes.isNullOrEmpty()) {
            return idPrefixes.any { id.startsWith(it) }
        }
        return true
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("version", version); put("name", name)
        description?.let { put("description", it) }
        logo?.let        { put("logo", it) }
        background?.let  { put("background", it) }
        put("types",     JSONArray(types))
        put("resources", JSONArray(resources))
        idPrefixes?.let  { put("idPrefixes", JSONArray(it)) }
        put("catalogs",  JSONArray(catalogs.map { it.toJson() }))
    }

    companion object {
        fun fromJson(o: JSONObject): AddonManifest {
            fun strList(key: String) = (o.optJSONArray(key) ?: JSONArray())
                .let { arr -> (0 until arr.length()).map { arr.getString(it) } }

            // resources can be array of strings OR objects — normalise to strings
            val rawRes = o.optJSONArray("resources") ?: JSONArray()
            val resList = (0 until rawRes.length()).map { i ->
                when (val v = rawRes.get(i)) {
                    is String     -> v
                    is JSONObject -> v.optString("name", "")
                    else          -> ""
                }
            }.filter { it.isNotEmpty() }

            val cats = (o.optJSONArray("catalogs") ?: JSONArray())
                .let { arr -> (0 until arr.length()).map { ManifestCatalog.fromJson(arr.getJSONObject(it)) } }

            return AddonManifest(
                id          = o.getString("id"),
                version     = o.optString("version", "1.0.0"),
                name        = o.optString("name", ""),
                description = o.optString("description", null),
                logo        = o.optString("logo",        null),
                background  = o.optString("background",  null),
                types       = strList("types"),
                resources   = resList,
                idPrefixes  = if (o.has("idPrefixes")) strList("idPrefixes") else null,
                catalogs    = cats
            )
        }
    }
}

data class ManifestCatalog(
    val type: String,
    val id:   String,
    val name: String     = ""
) {
    fun toJson() = JSONObject().apply { put("type", type); put("id", id); put("name", name) }
    companion object {
        fun fromJson(o: JSONObject) = ManifestCatalog(
            type = o.optString("type", ""),
            id   = o.optString("id",   ""),
            name = o.optString("name", "")
        )
    }
}

// ── Stream (Stremio protocol response) ───────────────────────────────────────

data class AddonStream(
    // Exactly one of these must be set:
    val url:       String?    = null,   // direct http(s) URL
    val infoHash:  String?    = null,   // torrent info hash
    val ytId:      String?    = null,   // YouTube video ID
    val externalUrl: String?  = null,   // external URL (e.g. Netflix page)

    // Optional metadata
    val name:        String?  = null,   // stream name / quality label
    val description: String?  = null,   // stream description (was "title")
    val thumbnail:   String?  = null,
    val subtitles:   List<AddonSubtitle> = emptyList(),
    val behaviorHints: StreamBehaviorHints = StreamBehaviorHints()
) {
    /** Convert to StreamResult for ExoPlayer */
    fun toStreamResult(source: String): StreamResult? {
        val streamUrl = url ?: return null
        if (!streamUrl.startsWith("http")) return null

        val quality = parseQuality(name ?: description ?: "")
        val type    = when {
            streamUrl.contains(".m3u8") || (behaviorHints.notWebReady == false &&
                streamUrl.contains("hls"))     -> StreamType.HLS
            streamUrl.contains(".mpd")         -> StreamType.DASH
            streamUrl.contains(".mkv")         -> StreamType.MKV
            else                               -> StreamType.MP4
        }

        return StreamResult(
            url       = streamUrl,
            quality   = quality,
            type      = type,
            source    = source,
            label     = buildString {
                if (!name.isNullOrEmpty())        append(name)
                else if (!description.isNullOrEmpty()) append(description)
                else append("$quality • $source")
            },
            subtitles = subtitles.map {
                SubtitleTrack(url = it.url, language = it.lang, title = it.lang, mimeType = "text/vtt")
            },
            headers   = behaviorHints.proxyHeaders?.request ?: emptyMap()
        )
    }

    private fun parseQuality(s: String): String = when {
        s.contains("4K",   true) || s.contains("2160", true) -> "4K"
        s.contains("1080", true)                              -> "1080p"
        s.contains("720",  true)                              -> "720p"
        s.contains("480",  true)                              -> "480p"
        s.contains("360",  true)                              -> "360p"
        else                                                  -> "HD"
    }

    companion object {
        fun fromJson(o: JSONObject): AddonStream = AddonStream(
            url         = o.optString("url",         null),
            infoHash    = o.optString("infoHash",    null),
            ytId        = o.optString("ytId",        null),
            externalUrl = o.optString("externalUrl", null),
            name        = o.optString("name",        null),
            description = o.optString("description", null)
                ?: o.optString("title", null),   // "title" is deprecated alias
            thumbnail   = o.optString("thumbnail",   null),
            subtitles   = run {
                val arr = o.optJSONArray("subtitles") ?: return@run emptyList()
                (0 until arr.length()).mapNotNull {
                    runCatching { AddonSubtitle.fromJson(arr.getJSONObject(it)) }.getOrNull()
                }
            },
            behaviorHints = o.optJSONObject("behaviorHints")
                ?.let { StreamBehaviorHints.fromJson(it) }
                ?: StreamBehaviorHints()
        )
    }
}

data class AddonSubtitle(
    val url:  String,
    val lang: String = "en"
) {
    companion object {
        fun fromJson(o: JSONObject) = AddonSubtitle(
            url  = o.getString("url"),
            lang = o.optString("lang", o.optString("language", "en"))
        )
    }
}

data class StreamBehaviorHints(
    val notWebReady:    Boolean?                 = null,
    val bingeGroup:     String?                  = null,
    val proxyHeaders:   ProxyHeaders?            = null,
    val countryWhitelist: List<String>?          = null
) {
    companion object {
        fun fromJson(o: JSONObject) = StreamBehaviorHints(
            notWebReady  = if (o.has("notWebReady")) o.getBoolean("notWebReady") else null,
            bingeGroup   = o.optString("bingeGroup",  null),
            proxyHeaders = o.optJSONObject("proxyHeaders")?.let { ProxyHeaders.fromJson(it) }
        )
    }
}

data class ProxyHeaders(
    val request:  Map<String, String> = emptyMap(),
    val response: Map<String, String> = emptyMap()
) {
    companion object {
        fun fromJson(o: JSONObject) = ProxyHeaders(
            request  = parseMap(o.optJSONObject("request")),
            response = parseMap(o.optJSONObject("response"))
        )
        private fun parseMap(o: JSONObject?): Map<String, String> {
            if (o == null) return emptyMap()
            return buildMap { o.keys().forEach { k -> put(k, o.optString(k, "")) } }
        }
    }
}

// ── ResourcePath  (mirrors Stremio-core's ResourcePath) ──────────────────────

data class ResourcePath(
    val resource: String,        // "stream", "catalog", "meta"
    val type:     String,        // "movie", "series"
    val id:       String,        // "tt1234567" or "tt1234567:1:2"
    val extra:    Map<String, String> = emptyMap()   // search=..., genre=...
) {
    /** Build the URL path as Stremio does: /{resource}/{type}/{id}.json */
    fun toUrlPath(): String {
        val base = "/$resource/$type/${encode(id)}.json"
        if (extra.isEmpty()) return base
        val qs   = extra.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return "/$resource/$type/${encode(id)}/$qs.json"
    }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        .replace("+", "%20")
}
