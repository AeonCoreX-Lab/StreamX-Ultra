package com.aeoncorex.streamx.streaming

import android.util.Log
import org.jsoup.Jsoup

/**
 * JsProviderContext — bridges what JS provider modules need into Kotlin.
 *
 * Provider modules call:
 *   providerContext.axios.get(url, config)    → HTTP GET  (auto-parses JSON)
 *   providerContext.axios.post(url, body, cfg)→ HTTP POST
 *   providerContext.axios.head(url)           → HTTP HEAD (returns Location + final URL)
 *   providerContext.cheerio.load(html)        → HTML parser
 *   providerContext.getBaseUrl(key)           → live domain lookup (blocking)
 *   providerContext.commonHeaders             → shared UA headers
 *
 * CHANGE LOG (bundle-addon fix):
 *   • JsAxiosResponse: added responseHeaders field (lowercase key → value)
 *   • JsAxios.get()  : now returns responseHeaders for fetch polyfill to read
 *   • JsAxios.head() : NEW — HEAD request, returns Location + x-final-url in headers
 *   • JsAxios.post() : now returns responseHeaders
 *   • Raw string data is exposed; JSON auto-parsing is done in the JS wrapper
 *     injected by JsEngine (keeps Java layer simple, JS layer handles types)
 */
class JsProviderContext(
    private val providerKey: String
) {
    private val TAG = "JsProviderContext"

    @JvmField val axios         = JsAxios()
    @JvmField val cheerio       = JsCheerio()
    @JvmField val commonHeaders: Map<String, String> = mapOf(
        "User-Agent"      to HttpClient.DESKTOP_UA,
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    )
    @JvmField val getBaseUrl    = GetBaseUrlBridge(providerKey)
}

// ── JsAxiosResponse ───────────────────────────────────────────────────────────
/**
 * Mirrors a minimal axios response:
 *   { data, status, responseHeaders }
 *
 * responseHeaders keys are lower-cased.
 * The special key "x-final-url" holds the URL after all redirects.
 * The JS engine's smart wrapper auto-parses JSON from `data` — this class
 * always stores the raw body string so Rhino doesn't have to touch JSON.
 */
data class JsAxiosResponse(
    @JvmField val data:            String,
    @JvmField val status:          Int,
    @JvmField val error:           String?              = null,
    @JvmField val responseHeaders: Map<String, String>  = emptyMap()
)

// ── JsAxios ───────────────────────────────────────────────────────────────────
class JsAxios {
    private val TAG = "JsAxios"

    // ── GET ──────────────────────────────────────────────────────────────────

    @JvmName("get")
    fun get(url: String): JsAxiosResponse = get(url, null)

    @JvmName("get")
    fun get(url: String, config: Any?): JsAxiosResponse {
        return try {
            val headers = extractHeaders(config)
            val result  = HttpClient.fetchRaw(url, "GET", headers, followRedirects = true)
            Log.d(TAG, "GET $url → ${result.status} (${result.body.length} chars)")
            JsAxiosResponse(
                data            = result.body,
                status          = result.status,
                responseHeaders = result.responseHeaders
            )
        } catch (e: Exception) {
            Log.w(TAG, "GET failed $url: ${e.message}")
            JsAxiosResponse(data = "", status = 0, error = e.message)
        }
    }

    // ── POST ─────────────────────────────────────────────────────────────────

    @JvmName("post")
    fun post(url: String, body: String): JsAxiosResponse = post(url, body, null)

    @JvmName("post")
    fun post(url: String, body: String, config: Any?): JsAxiosResponse {
        return try {
            val headers     = extractHeaders(config)
            val contentType = headers["Content-Type"] ?: headers["content-type"]
                ?: "application/x-www-form-urlencoded"
            val result = HttpClient.fetchRaw(
                url, "POST", headers, body, contentType, followRedirects = true
            )
            Log.d(TAG, "POST $url → ${result.status}")
            JsAxiosResponse(
                data            = result.body,
                status          = result.status,
                responseHeaders = result.responseHeaders
            )
        } catch (e: Exception) {
            Log.w(TAG, "POST failed $url: ${e.message}")
            JsAxiosResponse(data = "", status = 0, error = e.message)
        }
    }

    // ── HEAD — NEW ────────────────────────────────────────────────────────────
    /**
     * HEAD without following redirects.
     * The JS wrapper exposes:
     *   response.headers.get("location")        → Location header value
     *   response.request.responseURL            → final URL (from x-final-url)
     */
    @JvmName("head")
    fun head(url: String): JsAxiosResponse = head(url, null)

    @JvmName("head")
    fun head(url: String, config: Any?): JsAxiosResponse {
        return try {
            val headers = extractHeaders(config)
            // followRedirects = false so we can capture Location ourselves
            val result = HttpClient.fetchRaw(url, "HEAD", headers, followRedirects = false)
            Log.d(TAG, "HEAD $url → ${result.status}, Location=${result.responseHeaders["location"]}")
            JsAxiosResponse(
                data            = "",
                status          = result.status,
                responseHeaders = result.responseHeaders   // includes location + x-final-url
            )
        } catch (e: Exception) {
            Log.w(TAG, "HEAD failed $url: ${e.message}")
            JsAxiosResponse(data = "", status = 0, error = e.message,
                responseHeaders = mapOf("x-final-url" to url))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun extractHeaders(config: Any?): Map<String, String> {
        if (config == null) return emptyMap()
        return try {
            // config can be a Rhino NativeObject or JSON string
            val map = mutableMapOf<String, String>()
            when (config) {
                is org.mozilla.javascript.NativeObject -> {
                    val hdrs = config.get("headers", config)
                    if (hdrs is org.mozilla.javascript.NativeObject) {
                        hdrs.ids.forEach { id ->
                            map[id.toString()] = hdrs.get(id.toString(), hdrs)?.toString() ?: ""
                        }
                    }
                }
                else -> {
                    val json = org.json.JSONObject(config.toString())
                    val hdrs = json.optJSONObject("headers") ?: return emptyMap()
                    hdrs.keys().forEach { k -> map[k] = hdrs.getString(k) }
                }
            }
            map
        } catch (e: Exception) { emptyMap() }
    }
}

// ── JsCheerio ─────────────────────────────────────────────────────────────────
class JsCheerio {
    @JvmName("load")
    fun load(html: String): JsCheerioDoc = JsCheerioDoc(html)
}

class JsCheerioDoc(html: String) {
    private val doc = Jsoup.parse(html)

    @JvmName("select")  fun select(selector: String): JsCheerioElements = JsCheerioElements(doc.select(selector))
    @JvmName("text")    fun text(): String = doc.text()
    @JvmName("html")    fun html(): String = doc.html()

    operator fun invoke(selector: String): JsCheerioElements = select(selector)
}

class JsCheerioElements(private val els: org.jsoup.select.Elements) {
    @JvmField val length: Int = els.size

    @JvmName("text")    fun text()                  = els.text()
    @JvmName("html")    fun html()                  = els.html()
    @JvmName("attr")    fun attr(name: String)       = els.attr(name)
    @JvmName("first")   fun first()                  = els.first()?.let { JsCheerioElement(it) }
    @JvmName("last")    fun last()                   = els.last()?.let  { JsCheerioElement(it) }
    @JvmName("eq")      fun eq(i: Int)               = els.getOrNull(i)?.let { JsCheerioElement(it) }
    @JvmName("find")    fun find(sel: String)        = JsCheerioElements(els.select(sel))
    @JvmName("filter")  fun filter(sel: String)      = JsCheerioElements(els.select(sel))
    @JvmName("each")    fun each(fn: (Int, JsCheerioElement) -> Unit) {
        els.forEachIndexed { i, el -> fn(i, JsCheerioElement(el)) }
    }
    @JvmName("map")     fun <T> map(fn: (Int, JsCheerioElement) -> T): List<T> =
        els.mapIndexed { i, el -> fn(i, JsCheerioElement(el)) }
    @JvmName("toArray") fun toArray() = els.map { JsCheerioElement(it) }.toTypedArray()
    @JvmName("parent")  fun parent()  = JsCheerioElements(org.jsoup.select.Elements(els.mapNotNull { it.parent() }))
}

class JsCheerioElement(private val el: org.jsoup.nodes.Element) {
    @JvmName("text")    fun text()             = el.text()
    @JvmName("html")    fun html()             = el.html()
    @JvmName("attr")    fun attr(name: String) = el.attr(name)
    @JvmName("find")    fun find(sel: String)  = JsCheerioElements(el.select(sel))
    @JvmName("parent")  fun parent()           = el.parent()?.let { JsCheerioElement(it) }
    @JvmField val tagName: String = el.tagName()
}

// ── GetBaseUrlBridge ──────────────────────────────────────────────────────────
class GetBaseUrlBridge(private val defaultKey: String) {
    /** Called as: await providerContext.getBaseUrl(key) — runs blocking (safe on IO thread) */
    @JvmName("invoke")
    operator fun invoke(key: String): String =
        kotlinx.coroutines.runBlocking { ModflixConfig.get(key) }
}
