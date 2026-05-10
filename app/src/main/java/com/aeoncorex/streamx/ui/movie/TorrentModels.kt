package com.aeoncorex.streamx.ui.movie

// ─── StreamLink & MovieType live in MovieModels.kt (same package) ─
// ─── TorrentResult (legacy compat) ───────────────────────────────
data class TorrentResult(
    val title:  String,
    val magnet: String,
    val seeds:  Int,
    val peers:  Int,
    val size:   String,
    val source: String
)

// ─── EZTV API Response Models ─────────────────────────────────────
data class EztvResponse(val torrents: List<EztvTorrent>?)
data class EztvTorrent(
    val title:      String,
    val magnet_url: String,
    val seeds:      Int,
    val peers:      Int,
    val size_bytes: Long,
    val episode:    String,
    val season:     String
)

// ═══════════════════════════════════════════════════════════════════
//  DubLanguage — Standalone sealed class
//  (MovieSourceScraper removed — এই class সরাসরি এখানে থাকবে)
//
//  Properties:
//    label          → e.g. "Hindi", "Tamil"
//    isNativeLang   → true = English (original lang, no "Dubbed" term)
//    searchKeywords → torrent query তে যোগ হবে, e.g. "Hindi Dubbed"
//    torrentTerms   → quality terms, e.g. "1080p", "720p"
// ═══════════════════════════════════════════════════════════════════
sealed class DubLanguage {
    abstract val label:          String
    abstract val isNativeLang:   Boolean
    abstract val searchKeywords: List<String>
    abstract val torrentTerms:   List<String>

    object English : DubLanguage() {
        override val label          = "English"
        override val isNativeLang   = true
        override val searchKeywords = listOf("")
        override val torrentTerms   = listOf("1080p BluRay", "WEB-DL 1080p", "720p WEB-DL")
    }

    object Hindi : DubLanguage() {
        override val label          = "Hindi"
        override val isNativeLang   = false
        override val searchKeywords = listOf("Hindi Dubbed", "Hindi Dub", "Dual Audio Hindi")
        override val torrentTerms   = listOf("1080p", "720p", "")
    }

    object Tamil : DubLanguage() {
        override val label          = "Tamil"
        override val isNativeLang   = false
        override val searchKeywords = listOf("Tamil Dubbed", "Tamil Dub", "Tamil")
        override val torrentTerms   = listOf("1080p", "720p", "")
    }

    object Telugu : DubLanguage() {
        override val label          = "Telugu"
        override val isNativeLang   = false
        override val searchKeywords = listOf("Telugu Dubbed", "Telugu Dub", "Telugu")
        override val torrentTerms   = listOf("1080p", "720p", "")
    }

    object Bengali : DubLanguage() {
        override val label          = "Bengali"
        override val isNativeLang   = false
        override val searchKeywords = listOf("Bengali Dubbed", "Bangla Dubbed", "Bengali Dub")
        override val torrentTerms   = listOf("1080p", "720p", "")
    }

    object Kannada : DubLanguage() {
        override val label          = "Kannada"
        override val isNativeLang   = false
        override val searchKeywords = listOf("Kannada Dubbed", "Kannada Dub")
        override val torrentTerms   = listOf("1080p", "720p", "")
    }

    object Malayalam : DubLanguage() {
        override val label          = "Malayalam"
        override val isNativeLang   = false
        override val searchKeywords = listOf("Malayalam Dubbed", "Malayalam Dub")
        override val torrentTerms   = listOf("1080p", "720p", "")
    }

    object Japanese : DubLanguage() {
        override val label          = "Japanese"
        override val isNativeLang   = false
        override val searchKeywords = listOf("Japanese", "JPN")
        override val torrentTerms   = listOf("1080p", "720p", "")
    }

    object DualAudio : DubLanguage() {
        override val label          = "Dual Audio"
        override val isNativeLang   = false
        override val searchKeywords = listOf("Dual Audio", "Dual", "Multi Audio")
        override val torrentTerms   = listOf("1080p", "720p", "")
    }

    companion object {
        fun fromCode(code: String): DubLanguage = when (code.lowercase()) {
            "hi", "hin", "hindi"     -> Hindi
            "ta", "tam", "tamil"     -> Tamil
            "te", "tel", "telugu"    -> Telugu
            "bn", "ben", "bengali"   -> Bengali
            "kn", "kan", "kannada"   -> Kannada
            "ml", "mal", "malayalam" -> Malayalam
            "ja", "jpn", "japanese"  -> Japanese
            "dual"                   -> DualAudio
            else                     -> English
        }
    }
}
