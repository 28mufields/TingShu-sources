package com.github.eprendre.sources_by_28mufields

import android.util.Base64
import android.webkit.CookieManager
import com.github.eprendre.tingshu.extensions.getCookie
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

internal object ITingShuHttp {
    const val BASE = "https://m.itingshu.net"
    const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"

    private var lastRequestAt = 0L
    private var blockedUntil = 0L
    private val cache = LinkedHashMap<String, Pair<Long, String>>()

    @Synchronized
    fun request(
        url: String,
        data: List<Pair<String, String>>? = null,
        extra: Map<String, String> = emptyMap(),
        cancelled: () -> Boolean = { false }
    ): String {
        val cookie = getCookie(url).orEmpty()
        val key = url + "|" + (data?.joinToString("&") { it.first + "=" + it.second } ?: "GET") +
            "|" + cookie.hashCode()
        val now = System.currentTimeMillis()

        cache[key]?.let {
            if (now - it.first < 600_000L) return it.second
        }

        val remaining = (blockedUntil - now + 999L) / 1000L
        require(remaining <= 0) { "站点限流，还需等待 " + remaining + " 秒；已缓存页面仍可查看" }

        var attempt = 0
        while (attempt < 3) {
            if (cancelled() || Thread.currentThread().isInterrupted) {
                throw java.util.concurrent.CancellationException()
            }

            val wait = lastRequestAt + 4000L - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            if (cancelled()) throw java.util.concurrent.CancellationException()

            val connection = Jsoup.connect(url)
                .userAgent(UA)
                .referrer(BASE + "/")
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .timeout(30_000)
                .maxBodySize(0)

            if (cookie.isNotEmpty()) connection.header("Cookie", cookie)
            extra.forEach { (k, v) -> connection.header(k, v) }

            if (data != null) {
                data.forEach { connection.data(it.first, it.second) }
                connection.method(Connection.Method.POST)
            } else {
                connection.method(Connection.Method.GET)
            }

            lastRequestAt = System.currentTimeMillis()
            val response = connection.execute()

            response.cookies().forEach { (k, v) ->
                CookieManager.getInstance().setCookie(url, k + "=" + v)
            }

            if (response.statusCode() == 429) {
                val retry = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1L, 86400L) ?: 60L
                blockedUntil = System.currentTimeMillis() + retry * 1000L
                throw IllegalStateException("站点限流，请在 " + retry + " 秒后重试；已缓存页面仍可查看")
            }

            require(response.statusCode() in 200..299) {
                "站点 HTTP " + response.statusCode() + "：" + URI(url).path
            }

            val html = response.body()
            if (handleChallenge(url, html)) {
                attempt++
                continue
            }

            if (html.contains("<title>Loading...</title>")) {
                throw IllegalStateException("未知站点验证格式")
            }

            if (
                html.contains("class=\"book-") ||
                html.contains("novel-text-list") ||
                html.trim().startsWith("{")
            ) {
                cache[key] = System.currentTimeMillis() to html
                while (cache.size > 32) {
                    val first = cache.keys.firstOrNull() ?: break
                    cache.remove(first)
                }
            }
            return html
        }

        throw IllegalStateException("站点 Cookie 验证失败；已停止重试，请稍后再试")
    }

    private fun handleChallenge(url: String, html: String): Boolean {
        val encoded = Regex("""var reversed\s*=\s*"([^"]+)"""")
            .find(html)?.groupValues?.getOrNull(1) ?: return false

        val code = String(
            Base64.decode(encoded.reversed(), Base64.DEFAULT),
            Charsets.UTF_8
        )
        val token = Regex("""var token\s*=\s*'([^']+)'""")
            .find(code)?.groupValues?.getOrNull(1)
            ?: throw IllegalStateException("站点 Cookie 格式已变化")
        val retry = Regex("""var retry\s*=\s*(\d+)""")
            .find(code)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

        CookieManager.getInstance().setCookie(
            url,
            "__51guid__=" + URLEncoder.encode(token, "UTF-8") + "; Path=/; Max-Age=7200; Secure"
        )
        CookieManager.getInstance().setCookie(
            url,
            "__51refresh__guid=" + retry + "; Path=/; Max-Age=3600"
        )
        CookieManager.getInstance().flush()
        return true
    }
}

internal object ITingShuAudioCache {
    private val values = LinkedHashMap<String, Pair<Long, String>>()

    @Synchronized
    fun get(page: String): String? {
        val hit = values[page] ?: return null
        return if (System.currentTimeMillis() - hit.first < 120_000L) hit.second else null
    }

    @Synchronized
    fun put(page: String, audio: String) {
        values[page] = System.currentTimeMillis() to audio
        while (values.size > 16) {
            val first = values.keys.firstOrNull() ?: break
            values.remove(first)
        }
    }
}
