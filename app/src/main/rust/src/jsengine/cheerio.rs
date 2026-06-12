// app/src/main/rust/src/jsengine/cheerio.rs
//
// Native cheerio backend using the `scraper` crate (html5ever + CSS
// selectors) — a real implementation of providerContext.cheerio, far more
// robust than the Jsoup-via-Rhino path or a JS regex shim.
//
// Bound to QuickJS as `__native_cheerio(html, selector) -> jsonArray`.
// Each matched element is serialized as { html, text, attrs: {...} }.
// The JS-side cheerio.js shim (see POLYFILLS in mod.rs) wraps these into
// chainable Cheerio-like objects ($('.x').find('a').attr('href') etc).

use scraper::{Html, Selector};
use serde::Serialize;

#[derive(Serialize)]
struct ElementJson {
    html: String,
    text: String,
    attrs: serde_json::Map<String, serde_json::Value>,
    tag: String,
}

/// Entry point bound to QuickJS as `__native_cheerio(html, selector) -> json`.
/// Returns "[]" on invalid selector or parse failure (never throws into JS).
pub fn native_cheerio(html: String, selector: String) -> String {
    let doc = Html::parse_fragment(&html);

    let sel = match Selector::parse(&selector) {
        Ok(s) => s,
        Err(e) => {
            log::warn!("[jsengine/cheerio] bad selector '{selector}': {e:?}");
            return "[]".to_string();
        }
    };

    let mut out = Vec::new();
    for el in doc.select(&sel) {
        let mut attrs = serde_json::Map::new();
        for (k, v) in el.value().attrs() {
            attrs.insert(k.to_string(), serde_json::Value::String(v.to_string()));
        }
        out.push(ElementJson {
            html: el.inner_html(),
            text: el.text().collect::<Vec<_>>().join(""),
            attrs,
            tag: el.value().name().to_string(),
        });
    }

    serde_json::to_string(&out).unwrap_or_else(|_| "[]".to_string())
}
