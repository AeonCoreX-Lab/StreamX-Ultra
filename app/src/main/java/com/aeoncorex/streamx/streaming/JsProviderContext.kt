package com.aeoncorex.streamx.streaming

import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import org.jsoup.Jsoup

/**
 * JsProviderContext — bridges what JS provider modules need into Kotlin.
 *
 * Provider modules call:
 *   providerContext.axios.get(url, config)  → HTTP GET
 *   providerContext.cheerio.load(html)      → HTML parser
 *   providerContext.getBaseUrl(key)         → live domain lookup
 *   providerContext.commonHeaders           → shared UA headers
 *
 * This class is injected into every JS execution via Rhino's Java bridge.
 */
class JsProviderContext(
    private val providerKey: String
) {
    private val TAG = "JsProviderContext"

    // ── axios bridge ──────────────────────────────────────────────────────────
    @JvmField val axios = JsAxios()

    // ── cheerio bridge ────────────────────────────────────────────────────────
    @JvmField val cheerio = JsCheerio()

    // ── commonHeaders — same UA as HttpClient ─────────────────────────────────
    @JvmField val commonHeaders: Map<String, String> = mapOf(
        "User-Agent"      to HttpClient.DESKTOP_UA,
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )

    // ── getBaseUrl ────────────────────────────────────────────────────────────
    /**
     * Called by JS as: await providerContext.getBaseUrl(providerKey)
     * In Rhino, suspend functions aren't directly usable — we call blocking.
     * This runs on a coroutine dispatcher thread, so blocking is safe.
     */
    @JvmField val getBaseUrl = GetBaseUrlBridge(providerKey)
}

// ── JsAxios — wraps OkHttp for JS callers ─────────────────────────────────────
class JsAxios {
    private val TAG = "JsAxios"

    /** GET request — returns a JS-compatible response object */
    @JvmName("get")
    fun get(url: String): JsAxiosResponse = get(url, null)

    @JvmName("get")
    fun get(url: String, config: Any?): JsAxiosResponse {
        return try {
            val headers = extractHeaders(config)
            val html    = HttpClient.getHtml(url, headers) ?: ""
            Log.d(TAG, "GET $url → ${html.length} chars")
            JsAxiosResponse(data = html, status = 200)
        } catch (e: Exception) {
            Log.w(TAG, "GET failed $url: ${e.message}")
            JsAxiosResponse(data = "", status = 0, error = e.message)
        }
    }

    /** POST request */
    @JvmName("post")
    fun post(url: String, body: String): JsAxiosResponse = post(url, body, null)

    @JvmName("post")
    fun post(url: String, body: String, config: Any?): JsAxiosResponse {
        return try {
            val headers = extractHeaders(config)
            val resp    = HttpClient.postJson(url, body, headers) ?: ""
            JsAxiosResponse(data = resp, status = 200)
        } catch (e: Exception) {
            JsAxiosResponse(data = "", status = 0, error = e.message)
        }
    }

    private fun extractHeaders(config: Any?): Map<String, String> {
        if (config == null) return emptyMap()
        return try {
            val json = JSONObject(config.toString())
            val hdrs = json.optJSONObject("headers") ?: return emptyMap()
            buildMap { hdrs.keys().forEach { k -> put(k, hdrs.getString(k)) } }
        } catch (e: Exception) { emptyMap() }
    }
}

data class JsAxiosResponse(
    @JvmField val data:   String,
    @JvmField val status: Int,
    @JvmField val error:  String? = null
)

// ── JsCheerio — wraps Jsoup for JS callers ────────────────────────────────────
class JsCheerio {
    /**
     * JS calls: const $ = providerContext.cheerio.load(html)
     * We return a JsCheerioDoc that mimics the cheerio $ function API.
     */
    @JvmName("load")
    fun load(html: String): JsCheerioDoc = JsCheerioDoc(html)
}

class JsCheerioDoc(html: String) {
    private val doc = Jsoup.parse(html)

    /** $(selector) → JsCheerioElement */
    @JvmName("select")
    fun select(selector: String): JsCheerioElements =
        JsCheerioElements(doc.select(selector))

    /** $.text() on the whole doc */
    @JvmName("text") fun text(): String = doc.text()
    @JvmName("html") fun html(): String = doc.html()

    /** Direct property-style access: $("selector").attr("href") etc. */
    operator fun invoke(selector: String): JsCheerioElements = select(selector)
}

class JsCheerioElements(private val els: org.jsoup.select.Elements) {
    @JvmField val length: Int = els.size

    @JvmName("text")   fun text()                  = els.text()
    @JvmName("html")   fun html()                  = els.html()
    @JvmName("attr")   fun attr(name: String)       = els.attr(name)
    @JvmName("first")  fun first()                  = els.first()?.let { JsCheerioElement(it) }
    @JvmName("last")   fun last()                   = els.last()?.let  { JsCheerioElement(it) }
    @JvmName("eq")     fun eq(i: Int)               = els.getOrNull(i)?.let { JsCheerioElement(it) }
    @JvmName("find")   fun find(sel: String)        = JsCheerioElements(els.select(sel))
    @JvmName("filter") fun filter(sel: String)      = JsCheerioElements(els.select(sel))
    @JvmName("each")   fun each(fn: (Int, JsCheerioElement) -> Unit) {
        els.forEachIndexed { i, el -> fn(i, JsCheerioElement(el)) }
    }
    @JvmName("map")    fun <T> map(fn: (Int, JsCheerioElement) -> T): List<T> =
        els.mapIndexed { i, el -> fn(i, JsCheerioElement(el)) }
    @JvmName("toArray") fun toArray() = els.map { JsCheerioElement(it) }.toTypedArray()
}

class JsCheerioElement(private val el: org.jsoup.nodes.Element) {
    @JvmName("text")  fun text()             = el.text()
    @JvmName("html")  fun html()             = el.html()
    @JvmName("attr")  fun attr(name: String) = el.attr(name)
    @JvmName("find")  fun find(sel: String)  = JsCheerioElements(el.select(sel))
    @JvmName("parent") fun parent()          = el.parent()?.let { JsCheerioElement(it) }
    @JvmField val tagName: String = el.tagName()
}

// ── GetBaseUrlBridge — blocking wrapper for getBaseUrl ───────────────────────
class GetBaseUrlBridge(private val defaultKey: String) {
    /** JS calls: await providerContext.getBaseUrl(key) — we run it blocking */
    @JvmName("invoke")
    operator fun invoke(key: String): String {
        return kotlinx.coroutines.runBlocking {
            ModflixConfig.get(key)
        }
    }
}
