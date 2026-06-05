package com.aeoncorex.streamx.streaming

import android.util.Log
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

/**
 * JsEngine — executes bundled CJS provider modules using Mozilla Rhino.
 *
 * Each module is a self-contained CJS bundle that exports functions like
 *   getStream, getPosts, getMeta, getEpisodes
 * via the `exports` object.
 *
 * Dependencies (axios, cheerio) are bridged to Kotlin via JsProviderContext.
 */
object JsEngine {
    private const val TAG = "JsEngine"

    /**
     * Execute a JS module string and return a map of its exports.
     * @param code    The bundled CJS JS code string
     * @param context The Kotlin-side bridge object (wraps OkHttp + Jsoup)
     */
    fun execModule(code: String, context: JsProviderContext): ScriptableObject {
        val rhino = Context.enter()
        return try {
            rhino.optimizationLevel = -1          // interpreted mode — safe for Android
            rhino.languageVersion   = Context.VERSION_ES6

            val scope = rhino.initStandardObjects()

            // Inject CJS shims
            val exports = rhino.newObject(scope)
            ScriptableObject.putProperty(scope, "exports",  exports)
            ScriptableObject.putProperty(scope, "module",   rhino.newObject(scope).also {
                ScriptableObject.putProperty(it, "exports", exports)
            })
            ScriptableObject.putProperty(scope, "require",  Context.javaToJS({ _: Any -> rhino.newObject(scope) }, scope))
            ScriptableObject.putProperty(scope, "console",  Context.javaToJS(JsConsole, scope))

            // Inject providerContext — this is what providers call for HTTP + parsing
            ScriptableObject.putProperty(scope, "providerContext", Context.javaToJS(context, scope))

            // Wrap code to handle both CJS patterns:
            //   exports.fn = ...
            //   module.exports = { fn: ... }
            val wrapped = """
                (function(exports, module, require, console, providerContext) {
                    $code
                    // Merge module.exports back into exports if providers used module.exports
                    var me = module.exports;
                    if (me && typeof me === 'object') {
                        Object.keys(me).forEach(function(k) { exports[k] = me[k]; });
                    }
                })(exports, module, require, console, providerContext);
            """.trimIndent()

            rhino.evaluateString(scope, wrapped, "provider", 1, null)

            scope.get("exports", scope) as ScriptableObject
        } catch (e: Exception) {
            Log.e(TAG, "JS exec error: ${e.message}")
            throw e
        } finally {
            Context.exit()
        }
    }
}

/** Bridges console.log/warn/error to Android Log */
object JsConsole {
    @JvmStatic fun log(msg: Any?)   = Log.d("JS", msg?.toString() ?: "null")
    @JvmStatic fun warn(msg: Any?)  = Log.w("JS", msg?.toString() ?: "null")
    @JvmStatic fun error(msg: Any?) = Log.e("JS", msg?.toString() ?: "null")
}
