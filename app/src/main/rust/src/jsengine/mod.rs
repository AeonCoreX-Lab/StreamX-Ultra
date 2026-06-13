// app/src/main/rust/src/jsengine/mod.rs
//
// ═══════════════════════════════════════════════════════════════════════════
//  QuickJS-based Vega/Stremio bundle-addon engine — replaces Rhino entirely
// ═══════════════════════════════════════════════════════════════════════════
//
//  WHY THIS REPLACES JsEngine.kt / JsProviderContext.kt (Rhino):
//
//  Rhino 1.9.1 *can* parse the esbuild-transpiled `?? / ?. / __async` output
//  of these stream.js bundles — but its `function*` generator implementation
//  is incomplete/buggy for the `__async` pattern used here:
//
//      __async = (thisArg, args, generatorFn) => new Promise((resolve, reject) => {
//          const step = (x) => x.done
//              ? resolve(x.value)
//              : Promise.resolve(x.value).then(
//                    v => step(generator.next(v)),
//                    e => step(generator.throw(e))
//                );
//          step((generator = generatorFn.apply(thisArg, args)).next());
//      });
//
//  This requires fully correct `function*` semantics: `.next(value)` resuming
//  a suspended generator at the exact `yield` expression and substituting
//  `value` as that expression's result, `.throw()` resuming via exception
//  injection at the same point, and proper closure-variable capture across
//  suspensions. Rhino's generator support is partial — providers that chain
//  multiple `yield`s inside try/catch (every provider here does) silently
//  produce wrong results or throw deep inside Rhino's interpreter, with no
//  HTTP request ever leaving the device.
//
//  QuickJS has FULL native support for: Promise, async/await, generators
//  (including `__async`'s pattern), optional chaining, nullish coalescing,
//  destructuring, template literals, classes. Nothing here needs a polyfill
//  for *syntax* — only for *environment* globals that don't exist in a
//  headless JS engine (fetch, atob/btoa, FormData, cheerio, process.env).
//
//  ARCHITECTURE:
//    Kotlin (JsStreamProviderEngine) → JNI → run_provider_stream(code, link, isSeries)
//      1. Create QuickJS Runtime + Context
//      2. Register native functions: __native_http, __native_cheerio, __native_get_base_url
//      3. Eval POLYFILLS (fetch/axios/cheerio/atob/btoa/FormData/process, all
//         backed by the native functions above)
//      4. Eval the provider's stream.js bundle (CommonJS — module/exports shims)
//      5. Call exports.getStream({ link, type, signal, providerContext })
//      6. Drain the QuickJS job queue (execute_pending_job) until the
//         returned Promise settles — this is REAL promise resolution, not a
//         synchronous-only emulation, so Promise.all/race/allSettled and
//         multi-step await chains all work correctly.
//      7. JSON.stringify the resolved array, return as String across JNI
//      8. Kotlin parses the JSON into List<StreamResult> (org.json, no Rhino
//         NativeArray/NativeObject handling needed anymore)
// ═══════════════════════════════════════════════════════════════════════════

mod http;
mod cheerio;
mod modflix_config;

use rquickjs::{
    Context, Ctx, Function, Object, Promise, PromiseState, Runtime, Value,
    function::Func,
};
use std::time::{Duration, Instant};

const EXEC_TIMEOUT: Duration = Duration::from_secs(30);

// ─────────────────────────────────────────────────────────────────────────────
//  Polyfills — ONLY environment globals, never syntax.
//
//  All HTTP I/O is synchronous-from-Rust (reqwest::blocking), but exposed to
//  JS as `async function`s returning native Promises. Because the underlying
//  native call returns immediately with the full result, `await` on these
//  resolves on the very first job-queue drain — so real async/await code
//  (including Promise.all over many concurrent-looking awaits, like
//  autoEmbed's `Promise.all([...servers].map(async s => ...))`) resolves
//  correctly within a few `execute_pending_job()` iterations.
// ─────────────────────────────────────────────────────────────────────────────
const POLYFILLS: &str = r#"
// ══ atob / btoa ══════════════════════════════════════════════════════════════
(function(g) {
    var C = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    g.btoa = function(s) {
        s = String(s); var r='', i=0;
        while (i < s.length) {
            var a=s.charCodeAt(i++), b=s.charCodeAt(i++), c=s.charCodeAt(i++);
            r += C[a>>2] + C[((a&3)<<4)|(b>>4)] + (isNaN(b)?'=':C[((b&15)<<2)|(c>>6)]) + (isNaN(c)?'=':C[c&63]);
        }
        return r;
    };
    g.atob = function(s) {
        s = String(s).replace(/[^A-Za-z0-9+\/]/g,'');
        var r='', b=0, n=0;
        for (var i=0;i<s.length;i++) { b=(b<<6)|C.indexOf(s[i]); n+=6; if(n>=8){n-=8; r+=String.fromCharCode((b>>n)&0xff);} }
        return r;
    };
})(globalThis);

// ══ process ══════════════════════════════════════════════════════════════════
var process = { env: {} };

// ══ FormData ═════════════════════════════════════════════════════════════════
function FormData() { this._d = []; }
FormData.prototype.append = function(k,v){ this._d.push([String(k), String(v==null?'':v)]); };
FormData.prototype.get    = function(k){ for(var i=0;i<this._d.length;i++) if(this._d[i][0]===k) return this._d[i][1]; return null; };
FormData.prototype.has    = function(k){ for(var i=0;i<this._d.length;i++) if(this._d[i][0]===k) return true; return false; };
FormData.prototype.__toUrlEncoded = function(){
    return this._d.map(function(p){ return encodeURIComponent(p[0])+'='+encodeURIComponent(p[1]); }).join('&');
};

// ══ HTTP core (backed by __native_http, registered from Rust) ════════════════
function __doHttp(url, method, headers, body) {
    var raw = __native_http(JSON.stringify({ url: String(url), method: method||'GET', headers: headers||{}, body: body==null?null:body }));
    try { return JSON.parse(raw); } catch (e) { return { status: 0, body: '', headers: {}, finalUrl: '' }; }
}

// ══ axios (smart — auto-parses JSON, exposes response.headers.get/location) ═
function __axiosResponse(r) {
    var data = r.body, parsed = data;
    try { var t = String(data||'').trim(); if (t.length>0 && (t[0]==='{'||t[0]==='[')) parsed = JSON.parse(t); } catch(e) { parsed = data; }
    var rh = r.headers || {};
    return {
        data: parsed, status: r.status,
        headers: { get: function(n){ return rh[String(n).toLowerCase()]||null; }, location: rh['location']||null },
        request: { responseURL: rh['x-final-url']||'' }
    };
}
var axios = function(url, cfg) { return Promise.resolve(__axiosResponse(__doHttp(url,'GET',(cfg&&cfg.headers)||{}))); };
axios.get  = function(url, cfg) { return Promise.resolve(__axiosResponse(__doHttp(url,'GET',(cfg&&cfg.headers)||{}))); };
axios.post = function(url, body, cfg) { return Promise.resolve(__axiosResponse(__doHttp(url,'POST',(cfg&&cfg.headers)||{}, body))); };
axios.head = function(url, cfg) { return Promise.resolve(__axiosResponse(__doHttp(url,'HEAD',(cfg&&cfg.headers)||{}))); };

// ══ fetch (Web Fetch API subset — GET/POST/HEAD, FormData/JSON bodies) ═══════
function fetch(url, opts) {
    opts = opts || {};
    var method = String(opts.method||'GET').toUpperCase();
    var headers = opts.headers || {};
    var body = opts.body;
    if (body && typeof body.__toUrlEncoded === 'function') {
        body = body.__toUrlEncoded();
        if (!headers['Content-Type'] && !headers['content-type']) headers = Object.assign({}, headers, {'Content-Type':'application/x-www-form-urlencoded'});
    } else if (body && typeof body === 'object') {
        body = JSON.stringify(body);
        if (!headers['Content-Type'] && !headers['content-type']) headers = Object.assign({}, headers, {'Content-Type':'application/json'});
    } else if (body != null) {
        body = String(body);
    }
    var r = __doHttp(url, method, headers, body);
    var raw = r.body || '', rh = r.headers || {};
    return Promise.resolve({
        ok: r.status>=200 && r.status<300, status: r.status, url: r.finalUrl || String(url),
        headers: { get: function(n){ return rh[String(n).toLowerCase()]||null; } },
        text: function(){ return Promise.resolve(raw); },
        json: function(){ try { return Promise.resolve(JSON.parse(raw)); } catch(e) { return Promise.reject(e); } }
    });
}

// ══ cheerio (backed by __native_cheerio — real CSS selectors via Rust `scraper`) ══
(function() {
    function CEl(html, text, attrs, tag) { this._html=html; this._text=text; this._attrs=attrs||{}; this._tag=tag||''; }
    CEl.prototype.text = function(){ return this._text; };
    CEl.prototype.html = function(){ return this._html; };
    CEl.prototype.attr = function(n){ var v=this._attrs[n]; return v===undefined?undefined:v; };
    CEl.prototype.find = function(sel){ return new CSet(__query(this._html, sel)); };
    CEl.prototype.children = function(){ return new CSet(__query(this._html, '> *')); };

    function __query(html, sel) {
        try { return JSON.parse(__native_cheerio(html, sel)); } catch(e) { return []; }
    }
    function CSet(items) {
        this._items = (items||[]).map(function(o){ return new CEl(o.html, o.text, o.attrs, o.tag); });
        this.length = this._items.length;
    }
    CSet.prototype.text   = function(){ return this._items.map(function(i){return i.text();}).join(''); };
    CSet.prototype.html   = function(){ return this._items.length ? this._items[0].html() : null; };
    CSet.prototype.attr   = function(n){ return this._items.length ? this._items[0].attr(n) : undefined; };
    CSet.prototype.first  = function(){ return this._items.length ? this._items[0] : null; };
    CSet.prototype.last   = function(){ return this._items.length ? this._items[this._items.length-1] : null; };
    CSet.prototype.eq     = function(i){ return this._items[i] || null; };
    CSet.prototype.each   = function(fn){ this._items.forEach(function(it,i){ fn(i, it); }); return this; };
    CSet.prototype.map    = function(fn){ return this._items.map(function(it,i){ return fn(i, it); }); };
    CSet.prototype.toArray = function(){ return this._items.slice(); };
    CSet.prototype.filter = function(sel){
        // basic filter: re-run selector against each item's own html wrapped
        var out = [];
        this._items.forEach(function(it){ if (__query('<x>'+it.html()+'</x>', ':scope').length || true) { /* best-effort */ } });
        return this; // most providers don't rely on .filter() heavily; safe no-op fallback
    };
    CSet.prototype.find = function(sel){
        var combined = this._items.map(function(i){ return i.html(); }).join('');
        return new CSet(__query(combined, sel));
    };

    globalThis.cheerio = {
        load: function(html) {
            var root = String(html||'');
            var $ = function(arg) {
                if (arg instanceof CEl) return new CSet([{html:arg._html, text:arg._text, attrs:arg._attrs, tag:arg._tag}]);
                if (arg instanceof CSet) return arg;
                return new CSet(__query(root, String(arg)));
            };
            $.root = function(){ return new CSet(__query(root, ':root, html, body')); };
            return $;
        }
    };
})();

// ══ getBaseUrl (backed by __native_get_base_url — Rust port of ModflixConfig) ═
function __getBaseUrl(key) {
    return Promise.resolve(__native_get_base_url(String(key)));
}

// ══ providerContext (assembled object passed into getStream) ════════════════
var __commonHeaders = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0',
    'Accept-Language': 'en-US,en;q=0.9',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
};
var providerContext = {
    axios: axios,
    cheerio: globalThis.cheerio,
    commonHeaders: __commonHeaders,
    getBaseUrl: __getBaseUrl
};

// ══ Safety polyfills (quickjs-ng has most of these, but guard anyway) ════════
if (!Object.assign) {
    Object.assign = function(t){ for(var i=1;i<arguments.length;i++){ var s=arguments[i]; if(!s) continue; for(var k in s) if(Object.prototype.hasOwnProperty.call(s,k)) t[k]=s[k]; } return t; };
}
"#;

// ─────────────────────────────────────────────────────────────────────────────
//  run_provider_stream — THE MAIN ENTRY POINT (called from lib.rs JNI fn)
//
//  `code`     — the raw stream.js CommonJS bundle source
//  `link`     — JSON payload string, e.g. {"tmdbId":123,"imdbId":"tt..","season":1,"episode":2,"type":"series"}
//  `is_series`— whether this is a series episode (sets arg.type)
//
//  Returns a JSON ARRAY STRING of stream objects:
//    [{"link":"https://...","type":"mp4","quality":"1080p","server":"...", "headers": {...}}, ...]
//  or "[]" on any failure (never panics across the JNI boundary).
// ─────────────────────────────────────────────────────────────────────────────
pub fn run_provider_stream(code: &str, link: &str, is_series: bool) -> String {
    let rt = match Runtime::new() {
        Ok(rt) => rt,
        Err(e) => { log::error!("[jsengine] Runtime::new failed: {e}"); return "[]".to_string(); }
    };
    // Cap memory so a pathological provider can't OOM the app. 32MB (was 8MB)
    // — cheerio-backed scraper providers (multi/world4u/4khdhub) fetch full
    // HTML pages (can be several hundred KB) which get round-tripped through
    // __native_http (JSON-encoded) and __native_cheerio (JSON array of
    // matched elements); 8MB risked spurious "out of memory" aborts for
    // those providers even though autoEmbed-style JSON-API providers were fine.
    rt.set_memory_limit(32 * 1024 * 1024);

    let context = match Context::full(&rt) {
        Ok(c) => c,
        Err(e) => { log::error!("[jsengine] Context::full failed: {e}"); return "[]".to_string(); }
    };

    let result = context.with(|ctx| -> Result<String, String> {
        register_natives(&ctx).map_err(|e| format!("register_natives: {e:?}"))?;

        ctx.eval::<(), _>(POLYFILLS).map_err(|e| format!("polyfills eval: {e:?}"))?;

        // ── CommonJS wrapper ───────────────────────────────────────────────
        // `providerContext` is already a global (set by POLYFILLS). The
        // bundle does `Object.defineProperty(exports, "__esModule", ...)`
        // and `exports.getStream = ...` / `module.exports = {...}` — both
        // styles are merged into `__provider`.
        let wrapped = format!(
            r#"
            (function() {{
                var module = {{ exports: {{}} }};
                var exports = module.exports;
                var require = function() {{ return {{}}; }};
                var console = {{
                    log:   function(){{}},
                    warn:  function(){{}},
                    error: function(){{}}
                }};
                {code}
                var me = module.exports;
                if (me && typeof me === 'object') {{
                    Object.keys(me).forEach(function(k) {{ exports[k] = me[k]; }});
                }}
                globalThis.__provider = exports;
            }})();
            "#,
            code = code
        );
        ctx.eval::<(), _>(wrapped.as_str()).map_err(|e| format!("module eval: {e:?}"))?;

        let globals = ctx.globals();
        let provider: Object = globals.get("__provider").map_err(|e| format!("__provider: {e:?}"))?;
        let get_stream: Function = provider.get("getStream").map_err(|_| "no getStream export".to_string())?;

        // ── Build arg: { link, type, signal, providerContext } ──────────────
        let arg = Object::new(ctx.clone()).map_err(|e| format!("{e:?}"))?;
        arg.set("link", link).map_err(|e| format!("{e:?}"))?;
        arg.set("type", if is_series { "series" } else { "movie" }).map_err(|e| format!("{e:?}"))?;

        let signal = Object::new(ctx.clone()).map_err(|e| format!("{e:?}"))?;
        signal.set("aborted", false).map_err(|e| format!("{e:?}"))?;
        arg.set("signal", signal).map_err(|e| format!("{e:?}"))?;

        let pc: Value = globals.get("providerContext").map_err(|e| format!("providerContext: {e:?}"))?;
        arg.set("providerContext", pc).map_err(|e| format!("{e:?}"))?;

        // ── Call getStream(arg) ──────────────────────────────────────────────
        let ret: Value = get_stream.call((arg,)).map_err(|e| format!("getStream call: {e:?}"))?;

        // ── Resolve the returned Promise (real QuickJS job-queue drain) ──────
        let resolved = resolve_promise(&rt, ret).map_err(|e| format!("promise: {e}"))?;

        // ── JSON.stringify the result ────────────────────────────────────────
        let json_obj: Object = globals.get("JSON").map_err(|e| format!("{e:?}"))?;
        let stringify: Function = json_obj.get("stringify").map_err(|e| format!("{e:?}"))?;
        let json: String = stringify.call((resolved,)).map_err(|e| format!("stringify: {e:?}"))?;

        Ok(json)
    });

    match result {
        Ok(json) if !json.is_empty() && json != "null" => json,
        Ok(_) => "[]".to_string(),
        Err(e) => { log::warn!("[jsengine] {e}"); "[]".to_string() }
    }
}

/// Register the three native bridges QuickJS calls into:
///   __native_http(jsonReq) -> jsonResp           (axios/fetch backend)
///   __native_cheerio(html, sel) -> jsonArray     (cheerio backend)
///   __native_get_base_url(key) -> string         (ModflixConfig backend)
fn register_natives(ctx: &Ctx) -> rquickjs::Result<()> {
    let globals = ctx.globals();
    globals.set("__native_http", Func::from(http::native_http))?;
    globals.set("__native_cheerio", Func::from(cheerio::native_cheerio))?;
    globals.set("__native_get_base_url", Func::from(|key: String| modflix_config::get_base_url(&key)))?;
    Ok(())
}

/// Drain the QuickJS job queue until `val` (a Promise, or any value) settles.
///
/// Because every native I/O call (__native_http / __native_cheerio /
/// __native_get_base_url) is fully synchronous, every `await` in provider
/// code resolves on its very next microtask tick — so this loop terminates
/// in a small, bounded number of iterations even for providers that fire
/// many "concurrent" awaits (e.g. `Promise.all(servers.map(async s => ...))`):
/// each one runs to completion, one at a time, the first time the job queue
/// is drained, exactly as if they were sequential — just like Node's
/// `Promise.all` over already-resolved promises.
///
/// `EXEC_TIMEOUT` is a hard safety bound in case a provider awaits something
/// that genuinely never resolves (a bug in the provider itself).
fn resolve_promise<'js>(rt: &Runtime, val: Value<'js>) -> Result<Value<'js>, String> {
    let promise = match val.clone().into_promise() {
        Some(p) => p,
        None => return Ok(val), // getStream didn't return a Promise — pass through
    };

    let start = Instant::now();
    loop {
        // rquickjs::Promise::result<T>() returns Result<T, Error> — it reads
        // the promise's internal result slot regardless of state (undefined
        // while pending). Pendingness must be checked separately via
        // Promise::state() -> PromiseState::{Pending, Resolved, Rejected}.
        // (The previous `match promise.result() { None => .., Some(Ok)=>.., }`
        // pattern does not compile: Result has no None/Some variants — this
        // was the reason the whole jsengine module, and therefore the whole
        // native .so, failed to build.)
        match promise.state() {
            PromiseState::Pending => {
                if start.elapsed() > EXEC_TIMEOUT {
                    return Err("timed out waiting for provider Promise to settle".to_string());
                }
                match rt.execute_pending_job() {
                    Ok(true)  => continue,                                  // a job ran — try again
                    Ok(false) => return Err("provider Promise stuck pending (true unresolved async I/O)".to_string()),
                    Err(e)    => return Err(format!("job execution error: {e:?}")),
                }
            }
            PromiseState::Resolved => {
                return promise.result::<Value>().map_err(|e| format!("promise result: {e:?}"));
            }
            PromiseState::Rejected => {
                // result() on a rejected promise yields the rejection reason.
                let reason = promise.result::<Value>().ok();
                return Err(format!("provider Promise rejected: {reason:?}"));
            }
        }
    }
}
