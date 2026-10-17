package com.hearthassist.app.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    private const val USER_AGENT = "HearthAssist/1.0 (Android)"

    /** Downloads [url] fully. Gzip responses are left for the caller (parsers sniff gzip). */
    fun get(url: String, timeoutMs: Int = 30_000): ByteArray {
        var target = URL(url)
        repeat(5) {
            val conn = (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                when (val code = conn.responseCode) {
                    in 200..299 -> return conn.inputStream.use { it.readBytes() }
                    301, 302, 303, 307, 308 -> {
                        val location = conn.getHeaderField("Location") ?: throw IOException("HTTP $code without Location")
                        target = URL(target, location)
                    }
                    else -> throw IOException("HTTP $code for $url")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects for $url")
    }
}
