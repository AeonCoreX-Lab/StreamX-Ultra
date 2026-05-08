package com.aeoncorex.streamx.streaming.extractors

import android.util.Base64

// ─────────────────────────────────────────────────────────────────────────────
//  RiveKeyGen.kt — Kotlin port of autoEmbed's generateSecretKey() algorithm
//  Used to generate the secretKey parameter for rivestream.app API calls.
//  Ported from: vega-providers/dist/autoEmbed/stream.js → generateSecretKey()
// ─────────────────────────────────────────────────────────────────────────────
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
            val r    = id
            val num  = r.toLongOrNull()

            val (t, n) = if (num == null) {
                // Non-numeric id: use char code sum
                val sum = r.fold(0L) { acc, ch -> acc + ch.code }
                val tVal = C[(sum % C.size).toInt()]
                val nVal = (sum % r.length / 2).toInt()
                Pair(tVal, nVal)
            } else {
                // Numeric id
                val tVal = C[(num % C.size).toInt()]
                val nVal = (num % r.length / 2).toInt()
                Pair(tVal, nVal)
            }

            val i = r.substring(0, n) + t + r.substring(n)
            val h = innerHash(i)
            val o = outerHash(h)
            Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            "topSecret"
        }
    }

    // ── innerHash — custom unsigned 32-bit hash ───────────────────────────────
    private fun innerHash(input: String): String {
        val e = input
        var t = 0L
        for (n in e.indices) {
            val r = e[n].code.toLong()
            val v = ((t + (t shl 6) + (t shl 16) - t) and 0xFFFFFFFFL)
            val i2 = ((v shl (n % 5) or (v ushr (32 - n % 5))) and 0xFFFFFFFFL)
            t = ((v xor i2 xor ((r shl (n % 7) or (r ushr (8 - n % 7))) and 0xFF)) and 0xFFFFFFFFL)
            t = ((t + (t ushr 11) xor (t shl 3)) and 0xFFFFFFFFL)
        }
        t = t xor (t ushr 15)
        t = ((49842L * (t and 0xFFFFL) + ((49842L * (t ushr 16) and 0xFFFFL) shl 16)) and 0xFFFFFFFFL)
        t = t xor (t ushr 13)
        t = ((40503L * (t and 0xFFFFL) + ((40503L * (t ushr 16) and 0xFFFFL) shl 16)) and 0xFFFFFFFFL)
        t = t xor (t ushr 16)
        return t.toString(16).padStart(8, '0')
    }

    // ── outerHash — second pass hash ─────────────────────────────────────────
    private fun outerHash(input: String): String {
        val e = input
        var n = (3735928559L xor e.length.toLong()) and 0xFFFFFFFFL
        for (idx in e.indices) {
            var r = e[idx].code.toLong()
            r = r xor ((131L * idx + 89 xor (r shl (idx % 5))) and 0xFF)
            n = ((n shl 7 or (n ushr 25)) and 0xFFFFFFFFL) xor r
            n = ((60205L * (n and 0xFFFFL)) + ((60205L * (n ushr 16) and 0xFFFFL) shl 16)) and 0xFFFFFFFFL
            n = n xor (n ushr 11)
        }
        n = n xor (n ushr 15)
        n = ((49842L * (n and 0xFFFFL)) + ((49842L * (n ushr 16) and 0xFFFFL) shl 16)) and 0xFFFFFFFFL
        n = n xor (n ushr 13)
        n = ((40503L * (n and 0xFFFFL)) + ((40503L * (n ushr 16) and 0xFFFFL) shl 16)) and 0xFFFFFFFFL
        n = n xor (n ushr 16)
        n = ((10196L * (n and 0xFFFFL)) + ((10196L * (n ushr 16) and 0xFFFFL) shl 16)) and 0xFFFFFFFFL
        n = n xor (n ushr 15)
        return n.toString(16).padStart(8, '0')
    }
}
