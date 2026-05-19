package com.aeoncorex.streamx.streaming.extractors

import android.util.Base64

/**
 * RiveKeyGen — Exact Kotlin port of generateSecretKey() from autoEmbed/stream.ts
 *
 * Fixed bugs vs previous version:
 *  1. innerHash: was missing `r` (char code) in the update formula
 *  2. innerHash: parentheses bug — (t ushr 11) xor (t shl 3) NOT t ushr 11 xor t shl 3
 *  3. outerHash: same parentheses issues
 *  4. All operations use Long with 0xFFFFFFFFL mask for unsigned 32-bit semantics
 */
object RiveKeyGen {

    private val C = listOf(
        "4Z7lUo","gwIVSMD","PLmz2elE2v","Z4OFV0","SZ6RZq6Zc","zhJEFYxrz8","FOm7b0",
        "axHS3q4KDq","o9zuXQ","4Aebt","wgjjWwKKx","rY4VIxqSN","kfjbnSo","2DyrFA1M",
        "YUixDM9B","JQvgEj0","mcuFx6JIek","eoTKe26gL","qaI9EVO1rB","0xl33btZL",
        "1fszuAU","a7jnHzst6P","wQuJkX","cBNhTJlEOf","KNcFWhDvgT","XipDGjST",
        "PCZJlbHoyt","2AYnMZkqd","HIpJh","KH0C3iztrG","W81hjts92","rJhAT",
        "NON7LKoMQ","NMdY3nsKzI","t4En5v","Qq5cOQ9H","Y9nwrp","VX5FYVfsf","cE5SJG",
        "x1vj1","HegbLe","zJ3nmt4OA","gt7rxW57dq","clIE9b","jyJ9g","B5jXjMCSx",
        "cOzZBZTV","FTXGy","Dfh1q1","ny9jqZ2POI","X2NnMn","MBtoyD","qz4Ilys7wB",
        "68lbOMye","3YUJnmxp","1fv5Imona","PlfvvXD7mA","ZarKfHCaPR","owORnX",
        "dQP1YU","dVdkx","qgiK0E","cx9wQ","5F9bGa","7UjkKrp","Yvhrj","wYXez5Dg3",
        "pG4GMU","MwMAu","rFRD5wlM"
    )

    fun generate(id: String?): String {
        if (id == null) return "rive"
        return try {
            val r   = id
            val num = r.toLongOrNull()

            // TS: t = c[num % c.length], n = Math.floor((num % r.length) / 2)
            val (t, n) = if (num == null) {
                val sum  = r.fold(0L) { acc, ch -> acc + ch.code }
                val tVal = C[(sum % C.size).toInt()]
                val nVal = ((sum % r.length) / 2).toInt()
                Pair(tVal, nVal)
            } else {
                val tVal = C[(num % C.size).toInt()]
                val nVal = ((num % r.length) / 2).toInt()
                Pair(tVal, nVal)
            }

            // TS: const i = r.slice(0, n) + t + r.slice(n)
            val i = r.substring(0, n) + t + r.substring(n)

            // TS: const o = outerHash(innerHash(i)); return btoa(o)
            val h = innerHash(i)
            val o = outerHash(h)
            Base64.encodeToString(o.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } catch (_: Exception) {
            "topSecret"
        }
    }

    // ── innerHash — exact port of TS innerHash() ──────────────────────────────
    // Key fixes:
    //   - FIX 1: `r + (t << 6) + (t << 16) - t` not `t + (t shl 6) + (t shl 16) - t`
    //   - FIX 2: `t + ((t >>> 11) ^ (t << 3))` uses inner parens for XOR
    //   - FIX 3: Bit-rotate of r: `(r << n%7) | (r >>> (8 - n%7))` clipped to 0xFF
    private fun innerHash(e: String): String {
        var t = 0L
        for (n in e.indices) {
            val r = e[n].code.toLong() and 0xFFFFL

            // TS: t = (r + (t << 6) + (t << 16) - t) >>> 0
            t = (r + (t shl 6) + (t shl 16) - t) and 0xFFFFFFFFL

            // TS: i = ((t << n%5) | (t >>> (32 - n%5))) >>> 0
            val shift = n % 5
            val i = ((t shl shift) or (t ushr (32 - shift))) and 0xFFFFFFFFL

            // TS: rRot = ((r << n%7) | (r >>> (8 - n%7))) >>> 0   — 8-bit rotate of r
            val rShift = n % 7
            val rRot   = ((r shl rShift) or (r ushr (8 - rShift))) and 0xFFL

            // TS: t = (t ^ (i ^ rRot)) >>> 0
            t = (t xor (i xor rRot)) and 0xFFFFFFFFL

            // TS: t = (t + ((t >>> 11) ^ (t << 3))) >>> 0
            t = (t + ((t ushr 11) xor (t shl 3))) and 0xFFFFFFFFL
        }

        // Final mixing — exact port of TS finalisation
        t = t xor (t ushr 15)
        t = ((t and 0xFFFFL) * 49842L + (((t ushr 16) * 49842L and 0xFFFFL) shl 16)) and 0xFFFFFFFFL
        t = t xor (t ushr 13)
        t = ((t and 0xFFFFL) * 40503L + (((t ushr 16) * 40503L and 0xFFFFL) shl 16)) and 0xFFFFFFFFL
        t = t xor (t ushr 16)
        return t.toString(16).padStart(8, '0')
    }

    // ── outerHash — exact port of TS outerHash() ────────────────────────────
    private fun outerHash(e: String): String {
        // TS: n = (3735928559 ^ t.length) >>> 0
        var n = (3735928559L xor e.length.toLong()) and 0xFFFFFFFFL

        for (idx in e.indices) {
            var r = e[idx].code.toLong() and 0xFFFFL

            // TS: r ^= ((131 * idx + 89) ^ (r << idx%5)) & 255
            r = r xor (((131L * idx + 89L) xor (r shl (idx % 5))) and 0xFFL)

            // TS: n = ((n << 7) | (n >>> 25)) >>> 0) ^ r
            n = ((n shl 7) or (n ushr 25)) and 0xFFFFFFFFL
            n = (n xor r) and 0xFFFFFFFFL

            // TS: n = ((n & 0xFFFF) * 60205 + (((n >>> 16) * 60205) << 16)) >>> 0
            n = ((n and 0xFFFFL) * 60205L + (((n ushr 16) * 60205L and 0xFFFFL) shl 16)) and 0xFFFFFFFFL

            // TS: n ^= n >>> 11
            n = (n xor (n ushr 11)) and 0xFFFFFFFFL
        }

        // Final mixing
        n = (n xor (n ushr 15)) and 0xFFFFFFFFL
        n = ((n and 0xFFFFL) * 49842L + (((n ushr 16) * 49842L and 0xFFFFL) shl 16)) and 0xFFFFFFFFL
        n = (n xor (n ushr 13)) and 0xFFFFFFFFL
        n = ((n and 0xFFFFL) * 40503L + (((n ushr 16) * 40503L and 0xFFFFL) shl 16)) and 0xFFFFFFFFL
        n = (n xor (n ushr 16)) and 0xFFFFFFFFL
        n = ((n and 0xFFFFL) * 10196L + (((n ushr 16) * 10196L and 0xFFFFL) shl 16)) and 0xFFFFFFFFL
        n = (n xor (n ushr 15)) and 0xFFFFFFFFL
        return n.toString(16).padStart(8, '0')
    }
}
