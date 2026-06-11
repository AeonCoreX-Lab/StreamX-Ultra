package com.aeoncorex.streamx.streaming

import android.util.Log
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.UniqueTag

/**
 * JsEngine — executes bundled Vega-style CJS provider modules using Mozilla Rhino.
 *
 * ── ROOT CAUSES FIXED ────────────────────────────────────────────────────────
 *
 *  ① Promise undefined → every getStream() throws before first HTTP request
 *    Fix: inject synchronous SyncPromise polyfill. JsAxios is blocking (OkHttp),
 *    so every `yield axios.get()` resolves immediately — no event loop needed.
 *
 *  ② atob/btoa undefined → 4khdhub, multi, autoEmbed crash
 *    Fix: inject pure-JS base64 polyfill.
 *
 *  ③ fetch undefined → world4u, multi, flixhq crash
 *    Fix: inject synchronous fetch backed by the same JsAxios/OkHttp client,
 *    supporting GET/POST/HEAD, FormData body, response headers, .text()/.json().
 *
 *  ④ process.env undefined → autoEmbed crashes on CORS_PRXY access
 *    Fix: inject `var process = { env: {} }`.
 *
 *  ⑤ FormData undefined → multi, world4u crash
 *    Fix: inject lightweight FormData polyfill with __toUrlEncoded().
 *
 *  ⑥ Two Rhino contexts → NativeObject from dead context crashes
 *    Fix: executeAndCallStream() runs module execution + getStream() call in
 *    ONE Context lifetime. Old execModule() + separate callGetStream() pattern
 *    is replaced.
 *
 *  ⑦ axios.get().data is raw string, not parsed JSON
 *    Providers do `response.data.streams.forEach()` expecting an object.
 *    Fix: module wrapper replaces providerContext.axios with a smart JS proxy
 *    that auto-parses JSON responses and exposes response.headers.get(name).
 *
 *  ⑧ Wrong providerContext passed to getStream arg
 *    All providers destructure providerContext from getStream's argument:
 *      function*({ link, providerContext }) { const {axios} = providerContext; }
 *    If we pass the raw Java object, {axios} is the unpatched JsAxios.
 *    Fix: the module wrapper declares `var __patchedPC` in the outer (global)
 *    scope, assigns the smart JS providerContext to it, and executeAndCallStream
 *    passes __patchedPC as the arg.providerContext.
 *
 *  ⑨ Promise result discarded (NativeObject instead of NativeArray returned)
 *    Fix: resolvePromise() reads ._state/_value from SyncPromise before returning.
 */
object JsEngine {

    private const val TAG = "JsEngine"

    // ─────────────────────────────────────────────────────────────────────────
    //  Polyfills — injected into the global scope before module code runs
    // ─────────────────────────────────────────────────────────────────────────
    private val POLYFILLS = """
// ══ SyncPromise ══════════════════════════════════════════════════════════════
// .then() callbacks run synchronously — valid because JsAxios is blocking I/O.
(function(g) {
    function SP(executor) {
        this._state = 'pending'; this._value = undefined; this._error = undefined;
        var self = this;
        function res(val) {
            if (self._state !== 'pending') return;
            if (val && typeof val === 'object' && typeof val.then === 'function') { val.then(res, rej); return; }
            self._state = 'fulfilled'; self._value = val;
        }
        function rej(err) { if (self._state !== 'pending') return; self._state = 'rejected'; self._error = err; }
        try { executor(res, rej); } catch(e) { rej(e); }
    }
    SP.prototype.then = function(onF, onR) {
        if (this._state === 'fulfilled') {
            if (typeof onF === 'function') { try { return SP.resolve(onF(this._value)); } catch(e) { return SP.reject(e); } }
            return SP.resolve(this._value);
        }
        if (this._state === 'rejected') {
            if (typeof onR === 'function') { try { return SP.resolve(onR(this._error)); } catch(e) { return SP.reject(e); } }
            return SP.reject(this._error);
        }
        return SP.reject(new Error('[SyncPromise] still pending'));
    };
    SP.prototype.catch   = function(r) { return this.then(undefined, r); };
    SP.prototype.finally = function(f) {
        return this.then(function(v){try{if(f)f();}catch(e){}return v;}, function(e){try{if(f)f();}catch(e2){}throw e;});
    };
    SP.resolve = function(val) {
        if (val instanceof SP) return val;
        if (val && typeof val === 'object' && typeof val.then === 'function') return new SP(function(r,j){val.then(r,j);});
        return new SP(function(r){r(val);});
    };
    SP.reject  = function(err) { return new SP(function(r,j){j(err);}); };
    function toArr(ps) { if (ps instanceof Array) return ps; var a=[]; for(var i=0;i<ps.length;i++) a.push(ps[i]); return a; }
    SP.all = function(ps) {
        var arr = toArr(ps), results = new Array(arr.length);
        for (var i=0;i<arr.length;i++) {
            var p = SP.resolve(arr[i]);
            if (p._state==='rejected') return SP.reject(p._error);
            if (p._state!=='fulfilled') return SP.reject(new Error('[SyncPromise.all] item pending'));
            results[i] = p._value;
        }
        return SP.resolve(results);
    };
    SP.allSettled = function(ps) {
        return SP.resolve(toArr(ps).map(function(p){
            var r=SP.resolve(p);
            return r._state==='fulfilled'?{status:'fulfilled',value:r._value}:{status:'rejected',reason:r._error};
        }));
    };
    SP.race = function(ps) {
        var arr=toArr(ps); for(var i=0;i<arr.length;i++){var r=SP.resolve(arr[i]);if(r._state!=='pending')return r;}
        return SP.reject(new Error('[SyncPromise.race] none settled'));
    };
    SP.any = function(ps) {
        var arr=toArr(ps); for(var i=0;i<arr.length;i++){var r=SP.resolve(arr[i]);if(r._state==='fulfilled')return r;}
        return SP.reject(new Error('[SyncPromise.any] all rejected'));
    };
    g.Promise = SP;
})(this);

// ══ atob / btoa ══════════════════════════════════════════════════════════════
(function(g) {
    var C = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    g.btoa = function(s) {
        s = String(s); var r='',i=0;
        while(i<s.length){var a=s.charCodeAt(i++),b=s.charCodeAt(i++),c=s.charCodeAt(i++);
            r+=C[a>>2]+C[((a&3)<<4)|(b>>4)]+(isNaN(b)?'=':C[((b&15)<<2)|(c>>6)])+(isNaN(c)?'=':C[c&63]);}
        return r;
    };
    g.atob = function(s) {
        s=String(s).replace(/[^A-Za-z0-9+\/]/g,'');
        var r='',b=0,n=0;
        for(var i=0;i<s.length;i++){b=(b<<6)|C.indexOf(s[i]);n+=6;if(n>=8){n-=8;r+=String.fromCharCode((b>>n)&0xff);}}
        return r;
    };
})(this);

// ══ process ══════════════════════════════════════════════════════════════════
var process = { env: {} };

// ══ FormData ═════════════════════════════════════════════════════════════════
function FormData() { this._d = []; }
FormData.prototype.append = function(k,v){ this._d.push([String(k),String(v||'')]); };
FormData.prototype.get    = function(k){ for(var i=0;i<this._d.length;i++)if(this._d[i][0]===k)return this._d[i][1]; return null; };
FormData.prototype.has    = function(k){ for(var i=0;i<this._d.length;i++)if(this._d[i][0]===k)return true; return false; };
FormData.prototype.__toUrlEncoded = function(){
    return this._d.map(function(p){return encodeURIComponent(p[0])+'='+encodeURIComponent(p[1]);}).join('&');
};

// ══ Safety polyfills ═════════════════════════════════════════════════════════
if(!Object.assign){Object.assign=function(t){for(var i=1;i<arguments.length;i++){var s=arguments[i];if(!s)continue;for(var k in s)if(Object.prototype.hasOwnProperty.call(s,k))t[k]=s[k];}return t;};}
if(!Array.from){Array.from=function(iter){var a=[];for(var i=0;i<iter.length;i++)a.push(iter[i]);return a;};}
if(!Array.isArray){Array.isArray=function(v){return Object.prototype.toString.call(v)==='[object Array]'};}
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  Module wrapper
    //
    //  Key design:
    //  • Declares `var __patchedPC` in the global scope (so it survives the IIFE)
    //  • Inside the IIFE, builds a smart JS providerContext with auto-JSON axios
    //    and fetch(), then assigns it to __patchedPC
    //  • Module code runs with the smart providerContext in scope
    //  • After executeAndCallStream runs the IIFE, it reads __patchedPC from
    //    the Rhino scope and passes it as arg.providerContext to getStream()
    //
    //  This solves FIX ⑦+⑧: providers that do
    //    function*({ link, providerContext }) { const {axios} = providerContext; }
    //  will get the smart JS axios, not the raw Java JsAxios.
    // ─────────────────────────────────────────────────────────────────────────
    private fun wrapModule(code: String): String = """
var __patchedPC;
(function(exports, module, require, console, __PC) {

// ── Smart axios proxy (auto-parses JSON, exposes response headers) ────────────
function __sr(resp) {
    var raw = resp.data, parsed = raw;
    try { var t=String(raw||'').trim(); if(t.length>0&&(t.charAt(0)==='{' || t.charAt(0)==='[')) parsed=JSON.parse(t); } catch(e){ parsed=raw; }
    var rh = resp.responseHeaders || {};
    return { data:parsed, status:resp.status,
        headers:{ get:function(n){ return rh[String(n).toLowerCase()]||null; }, location:rh['location']||null },
        request:{ responseURL: rh['x-final-url']||'' } };
}
var __ax = __PC.axios;
var axios = function(url,cfg)       { return Promise.resolve(__sr(__ax.get(String(url),cfg||null))); };
axios.get  = function(url,cfg)      { return Promise.resolve(__sr(__ax.get(String(url),cfg||null))); };
axios.post = function(url,body,cfg) { return Promise.resolve(__sr(__ax.post(String(url),String(body||''),cfg||null))); };
axios.head = function(url,cfg) {
    var r=__ax.head(String(url),cfg||null), rh=r.responseHeaders||{};
    return Promise.resolve({ data:null, status:r.status,
        headers:{ get:function(n){ return rh[String(n).toLowerCase()]||null; }, location:rh['location']||null },
        request:{ responseURL: rh['x-final-url']||'' } });
};

// ── fetch (backed by same JsAxios/OkHttp) ─────────────────────────────────────
function fetch(url, opts) {
    opts=opts||{}; var method=String(opts.method||'GET').toUpperCase(), hdrs=opts.headers||{}, body=opts.body, resp;
    if (method==='HEAD') {
        resp=__ax.head(String(url),null);
    } else if (method==='POST') {
        var bodyStr;
        if (body && typeof body.__toUrlEncoded==='function') {
            bodyStr=body.__toUrlEncoded();
            if(!hdrs['Content-Type']&&!hdrs['content-type']) hdrs=Object.assign({},hdrs,{'Content-Type':'application/x-www-form-urlencoded'});
        } else if (body && typeof body==='object' && !(typeof body==='string')) {
            bodyStr=JSON.stringify(body);
            if(!hdrs['Content-Type']&&!hdrs['content-type']) hdrs=Object.assign({},hdrs,{'Content-Type':'application/json'});
        } else { bodyStr=String(body||''); }
        resp=__ax.post(String(url),bodyStr,{headers:hdrs});
    } else {
        resp=__ax.get(String(url),{headers:hdrs});
    }
    var raw=String(resp.data||''), rh=resp.responseHeaders||{};
    return Promise.resolve({ ok:resp.status>=200&&resp.status<300, status:resp.status, url:String(url),
        headers:{ get:function(n){ return rh[String(n).toLowerCase()]||null; } },
        text:function(){ return Promise.resolve(raw); },
        json:function(){ try{ return Promise.resolve(JSON.parse(raw)); } catch(e){ return Promise.reject(e); } }
    });
}

// ── Smart providerContext (JS wrapper) ────────────────────────────────────────
var providerContext = {
    axios: axios,
    cheerio: __PC.cheerio,
    commonHeaders: __PC.commonHeaders,
    getBaseUrl: function(key) { return Promise.resolve(__PC.getBaseUrl.invoke(String(key))); }
};

// Expose to outer scope so executeAndCallStream can pass it as arg.providerContext
__patchedPC = providerContext;

// ── Module code ───────────────────────────────────────────────────────────────
$code

// ── Merge module.exports → exports ────────────────────────────────────────────
var me = module.exports;
if (me && me !== exports && typeof me === 'object') {
    Object.keys(me).forEach(function(k){ exports[k]=me[k]; });
}

})(exports, module, require, console, providerContext);
""".trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    //  executeAndCallStream  — THE MAIN FIX (replaces execModule + callGetStream)
    //
    //  Runs module execution AND getStream() call inside ONE Rhino context.
    //  Returns the resolved value from the SyncPromise (NativeArray of streams),
    //  or null on error.
    // ─────────────────────────────────────────────────────────────────────────
    fun executeAndCallStream(
        code:     String,
        ctx:      JsProviderContext,
        link:     String,
        isSeries: Boolean
    ): Any? {
        val rhino = Context.enter()
        return try {
            rhino.optimizationLevel = -1
            rhino.languageVersion   = Context.VERSION_ES6

            val scope = rhino.initStandardObjects()

            // ① Polyfills
            rhino.evaluateString(scope, POLYFILLS, "polyfills", 1, null)

            // ② CJS shims
            val exports   = rhino.newObject(scope)
            val moduleObj = rhino.newObject(scope).also {
                ScriptableObject.putProperty(it, "exports", exports)
            }
            ScriptableObject.putProperty(scope, "exports",  exports)
            ScriptableObject.putProperty(scope, "module",   moduleObj)
            ScriptableObject.putProperty(scope, "require",
                Context.javaToJS({ _: Any -> rhino.newObject(scope) }, scope))
            ScriptableObject.putProperty(scope, "console",
                Context.javaToJS(JsConsole, scope))

            // ③ Raw Java JsProviderContext → referenced as __PC inside wrapper
            ScriptableObject.putProperty(scope, "providerContext",
                Context.javaToJS(ctx, scope))

            // ④ Execute wrapped module
            //    This: builds smart axios/fetch, creates JS providerContext,
            //    assigns it to __patchedPC in global scope, runs module code,
            //    populates exports.getStream.
            rhino.evaluateString(scope, wrapModule(code), "provider", 1, null)

            // ⑤ Retrieve the smart JS providerContext that was set in __patchedPC
            val patchedPC = scope.get("__patchedPC", scope)
                .takeIf { it != null && it !is UniqueTag }
                ?: Context.javaToJS(ctx, scope)   // fallback to Java object

            // ⑥ Retrieve getStream from exports
            val finalExports = scope.get("exports", scope) as? ScriptableObject
                ?: return null.also { Log.w(TAG, "exports not ScriptableObject") }
            val getStream = finalExports.get("getStream", finalExports) as? Function
                ?: return null.also { Log.w(TAG, "no getStream export") }

            // ⑦ Build call argument  { link, type, signal, providerContext }
            //    providerContext = the SMART JS wrapper (not Java JsProviderContext)
            val sig = rhino.newObject(scope).also {
                ScriptableObject.putProperty(it, "aborted", false)
            }
            val arg = rhino.newObject(scope).also {
                ScriptableObject.putProperty(it, "link",            link)
                ScriptableObject.putProperty(it, "type",            if (isSeries) "series" else "movie")
                ScriptableObject.putProperty(it, "signal",          sig)
                ScriptableObject.putProperty(it, "providerContext", patchedPC)
            }

            // ⑧ Call getStream({link, type, signal, providerContext})
            val result = getStream.call(rhino, scope, scope, arrayOf(arg))
            Log.d(TAG, "getStream raw result: ${result?.javaClass?.simpleName}")

            // ⑨ Unwrap SyncPromise to get the actual array
            resolvePromise(result)

        } catch (e: Exception) {
            Log.e(TAG, "executeAndCallStream: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            Context.exit()
        }
    }

    /** Unwrap SyncPromise: read _state/_value. If not a promise, return as-is. */
    private fun resolvePromise(value: Any?): Any? {
        if (value !is ScriptableObject) return value
        val state = ScriptableObject.getProperty(value, "_state")?.toString()
            ?: return value   // not a SyncPromise
        return when (state) {
            "fulfilled" -> ScriptableObject.getProperty(value, "_value")
                .also { Log.d(TAG, "Promise fulfilled → ${it?.javaClass?.simpleName}") }
            "rejected"  -> null.also {
                Log.w(TAG, "Promise rejected: ${ScriptableObject.getProperty(value, "_error")}")
            }
            else -> null.also { Log.w(TAG, "Promise still pending — true async I/O in provider") }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  execModule — kept for catalog / meta / episodes (non-stream) modules
    //  These don't call async functions so single-context is fine.
    // ─────────────────────────────────────────────────────────────────────────
    fun execModule(code: String, context: JsProviderContext): ScriptableObject {
        val rhino = Context.enter()
        return try {
            rhino.optimizationLevel = -1
            rhino.languageVersion   = Context.VERSION_ES6
            val scope = rhino.initStandardObjects()
            rhino.evaluateString(scope, POLYFILLS, "polyfills", 1, null)
            val exports = rhino.newObject(scope)
            ScriptableObject.putProperty(scope, "exports", exports)
            ScriptableObject.putProperty(scope, "module",  rhino.newObject(scope).also {
                ScriptableObject.putProperty(it, "exports", exports)
            })
            ScriptableObject.putProperty(scope, "require",
                Context.javaToJS({ _: Any -> rhino.newObject(scope) }, scope))
            ScriptableObject.putProperty(scope, "console",
                Context.javaToJS(JsConsole, scope))
            ScriptableObject.putProperty(scope, "providerContext",
                Context.javaToJS(context, scope))
            rhino.evaluateString(scope, wrapModule(code), "provider", 1, null)
            scope.get("exports", scope) as ScriptableObject
        } catch (e: Exception) {
            Log.e(TAG, "execModule error: ${e.message}")
            throw e
        } finally {
            Context.exit()
        }
    }
}

// ── JsConsole ─────────────────────────────────────────────────────────────────
object JsConsole {
    @JvmStatic fun log(msg: Any?)  = android.util.Log.d("JS", msg?.toString() ?: "null")
    @JvmStatic fun warn(msg: Any?) = android.util.Log.w("JS", msg?.toString() ?: "null")
    @JvmStatic fun error(msg: Any?)= android.util.Log.e("JS", msg?.toString() ?: "null")
    @JvmStatic fun log(a: Any?, b: Any?) = android.util.Log.d("JS", "$a $b")
    @JvmStatic fun log(a: Any?, b: Any?, c: Any?) = android.util.Log.d("JS", "$a $b $c")
    @JvmStatic fun log(a: Any?, b: Any?, c: Any?, d: Any?) = android.util.Log.d("JS", "$a $b $c $d")
}
