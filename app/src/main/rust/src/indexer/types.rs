// src/indexer/types.rs
//
// Unified torrent search result returned by all indexer sites.
// Serialized to JSON and passed to Kotlin via JNI.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct TorrentResult {
    pub title:      String,
    pub magnet:     String,
    pub size:       String,
    pub seeds:      u32,
    pub peers:      u32,
    pub source:     String,
    pub audio_tags: Vec<String>,
    pub quality:    String,
}

impl TorrentResult {
    /// Parse audio/language and quality tags from release title.
    /// Mirrors Jackett's title-normalisation filters in the YAML definitions.
    ///
    /// Covers three broad groups:
    ///   1. South-Asian dub terms (Hindi/Tamil/Telugu/etc. — original scope)
    ///   2. Drama-specific language/subtitle terms (Korean/Chinese/Turkish —
    ///      these titles usually say "ENG SUB", "English Dub", or name the
    ///      origin language explicitly rather than "Dubbed")
    ///   3. Anime dub/sub conventions ("Dual Audio [ENG-JAP]", "Dub", "Multi-Sub")
    pub fn parse_tags(&mut self) {
        let t = self.title.to_lowercase();

        // Order: more-specific patterns before generic ones to avoid double-tagging
        let audio_patterns: &[(&str, &str)] = &[
            // ── South Asian dubs (original scope) ──────────────────────────
            ("hindi dubbed",       "Hindi Dubbed"),
            ("hindi dub",          "Hindi Dubbed"),
            ("dual audio",         "Dual Audio"),
            ("dual.audio",         "Dual Audio"),
            ("multi audio",        "Multi Audio"),
            ("multi lang",         "Multi Lang"),
            ("dubbed",             "Dubbed"),
            ("hindi",               "Hindi"),
            ("tamil",                "Tamil"),
            ("telugu",               "Telugu"),
            ("bengali",              "Bengali"),
            ("bangla",               "Bangla"),
            ("malayalam",            "Malayalam"),
            ("kannada",              "Kannada"),
            ("marathi",              "Marathi"),

            // ── K-drama / C-drama / Turkish drama terms ─────────────────────
            // These sites/titles almost never say "Dubbed" — they name the
            // origin language and/or subtitle language explicitly.
            ("english dub",         "English Dub"),
            ("eng dub",             "English Dub"),
            ("eng sub",             "English Sub"),
            ("english sub",         "English Sub"),
            ("esub",                "English Sub"),
            ("multi-sub",           "Multi Sub"),
            ("multisub",            "Multi Sub"),
            ("hardsub",             "English Sub"),
            ("softsub",             "English Sub"),
            ("korean drama",        "Korean"),
            ("k-drama",             "Korean"),
            ("kdrama",              "Korean"),
            (" kor ",               "Korean"),
            ("chinese drama",       "Chinese"),
            ("c-drama",             "Chinese"),
            ("cdrama",              "Chinese"),
            ("mandarin",            "Chinese"),
            ("cantonese",           "Chinese"),
            ("turkish drama",       "Turkish"),
            ("turkish series",      "Turkish"),
            ("dizi",                "Turkish"), // "dizi" = Turkish for "series"
            ("turkce",              "Turkish"),
            ("türkçe",              "Turkish"),

            // ── Anime dub/sub conventions ────────────────────────────────────
            ("eng-jap",             "Dual Audio"),
            ("jap-eng",             "Dual Audio"),
            ("[dual audio]",        "Dual Audio"),
            ("dub]",                "English Dub"), // e.g. "[Dub]" tag suffix
            ("(dub)",               "English Dub"),
            ("[sub]",               "English Sub"),
            ("(sub)",               "English Sub"),
            ("raw]",                "Raw"),
            ("japanese",            "Japanese"),
        ];

        let mut tags: Vec<String> = Vec::new();
        for (pat, label) in audio_patterns {
            if t.contains(pat) {
                let l = label.to_string();
                if !tags.contains(&l) {
                    tags.push(l);
                }
            }
        }
        self.audio_tags = tags;

        self.quality = if t.contains("2160p") || t.contains("4k") || t.contains("uhd") {
            "4K"
        } else if t.contains("1080p") {
            "1080p"
        } else if t.contains("720p") {
            "720p"
        } else if t.contains("480p") {
            "480p"
        } else {
            "SD"
        }
        .to_string();
    }

    pub fn is_dubbed(&self) -> bool {
        !self.audio_tags.is_empty()
    }
}
