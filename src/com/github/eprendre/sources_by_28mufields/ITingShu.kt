package com.github.eprendre.sources_by_28mufields

import com.github.eprendre.tingshu.sources.*
import com.github.eprendre.tingshu.extensions.getCookie
import com.github.eprendre.tingshu.extensions.showToast
import com.github.eprendre.tingshu.extensions.notifyLoadingEpisodes
import com.github.eprendre.tingshu.utils.*
import com.github.kittinunf.fuel.Fuel
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

object ITingShu : TingShu(), AudioUrlExtraHeaders, ConfigurableSource {
    internal const val BASE = "https://m.itingshu.net"
    internal const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
    private val generation = AtomicInteger()
    private var activeBook = ""
    private val chapterStore = ChapterStore()
    @Synchronized private fun beginBook(book: String): Int {
        if (activeBook != book) { activeBook = book; generation.incrementAndGet() }
        return generation.get()
    }
    override fun getSourceId() = "3aa11119c74448efbd26cd3d16038bbc"
    override fun getUrl() = BASE
    override fun getName() = "爱听书"
    override fun getDesc() = "r5：支持 APP 预缓存；共享网页登录 Cookie；慢速目录。\n" + SiteHttp.status()
    override fun getCustomConfigItems(): List<ConfigItem> = listOf(
        ConfigItem.Button("查看源状态（最近错误/等待时间）") { showToast(SiteHttp.status()) },
        ConfigItem.Button("网页登录说明") { showToast("请在本 APP 播放菜单的“查看源网页”中登录 m.itingshu.net，再返回重试。外部浏览器登录不共享；检测到 Cookie 不等于已登录。") }
    )
    override fun isWebViewNotRequired() = true
    override fun isMultipleEpisodePages() = true
    override fun isCacheable() = true // Allow the host to cache audio; each extraction still resolves a fresh URL.
    override fun reset() { generation.incrementAndGet() }

    internal fun mobile(url: String): String {
        val uri = URI(BASE).resolve(url)
        require(uri.scheme in listOf("https", "http") && uri.host in listOf("m.itingshu.net", "www.itingshu.net")) { "非本站链接" }
        return BASE + uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
    }
    private fun doc(url: String, cancelled: () -> Boolean = { false }): Document {
        val u = mobile(url)
        return Jsoup.parse(SiteHttp.request(u, cancelled = cancelled), u)
    }
    internal fun parseBooks(d: Document): List<Book> = d.select("ol.book-ol li.book-li").mapNotNull { li ->
        val a = li.selectFirst("a.book-layout[href]") ?: return@mapNotNull null
        if (!a.attr("href").contains("/youshengxiaoshuo/")) return@mapNotNull null
        val img = a.selectFirst("img")
        Book(img?.absUrl("src") ?: "", mobile(a.absUrl("href")),
            a.select(".book-title").text().ifBlank { img?.attr("alt") ?: "" }, "", "").apply {
            intro = a.select(".book-desc").text()
            status = a.select(".tag-small").eachText().joinToString(" ")
            isCompleted = a.select(".tag-small").any { it.text() == "完结" }
            sourceId = getSourceId()
        }
    }
    private fun pageNumber(url: String): Int = Regex("/(\\d+)\\.html(?:\\?.*)?$").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    internal fun totalPages(d: Document, current: Int): Int = d.select(".page a[href]")
        .map { pageNumber(it.absUrl("href")) }.maxOrNull()?.coerceAtLeast(current) ?: current
    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        require(page >= 1)
        SiteHttp.ensureSession()
        val url: String
        val html: String
        if (page == 1) {
            url = "$BASE/novelsearch/search/result.html"
            html = SiteHttp.request(url, listOf("searchword" to keywords))
        } else {
            val encoded = URLEncoder.encode(keywords, "UTF-8").replace("%", "oOo")
            url = "$BASE/search/$encoded/lastupdate/$page.html"
            html = SiteHttp.request(url)
        }
        val d = Jsoup.parse(html, url)
        if (d.text().contains("抱歉，无相关内容")) return emptyList<Book>() to page
        check(d.select(".novel-title").text().contains("搜索")) { "搜索响应异常，请稍后重试" }
        return parseBooks(d) to totalPages(d, page)
    }
    override fun getCategoryMenus(): List<CategoryMenu> {
        val d = doc("/book/")
        val tabs = d.select("a.book-sort[href]").map { CategoryTab(it.select(".book-title").text(), mobile(it.absUrl("href"))) }
        check(tabs.isNotEmpty()) { "分类结构已变化" }
        return listOf(CategoryMenu("有声分类", tabs))
    }
    override fun getCategoryList(url: String): Category {
        val normalized = mobile(url)
        val d = doc(normalized)
        val current = pageNumber(normalized)
        val total = totalPages(d, current)
        val next = d.select(".page a[href]").firstOrNull { it.text() == "下一页" }?.absUrl("href") ?: ""
        return Category(parseBooks(d), current, total, normalized, if (current < total && next.isNotBlank()) mobile(next) else "")
    }
    override fun getBookDetailInfo(bookUrl: String, loadEpisodes: Boolean, loadFullPages: Boolean): BookDetail {
        val normalizedBook = mobile(bookUrl)
        val run = beginBook(normalizedBook)
        val cancelled = { generation.get() != run || Thread.currentThread().isInterrupted }
        val d = doc(normalizedBook, cancelled)
        check(d.selectFirst("#book-detail[data-bid]") != null) { "详情页结构已变化" }
        val author = d.select(".book-detail-info a[href^=/author/]").text()
        val artist = d.select(".book-detail-info a[href^=/boyin/]").eachText().joinToString("，")
        val intro = d.select(".cor-introduce").text()
        val count = Regex("共\\s*(\\d+)\\s*集").find(d.text())?.groupValues?.get(1)?.toInt() ?: 0
        val episodes = linkedMapOf<String, Episode>()
        if (loadEpisodes) {
            val link = d.select("a[href]").firstOrNull { it.text().contains("查看完整目录") }
                ?: error("未找到完整目录")
            val catalogUrl = mobile(link.absUrl("href"))
            val first = doc(catalogUrl, cancelled)
            val firstEpisodes = parseEpisodes(first)
            check(firstEpisodes.isNotEmpty()) { "目录为空或结构已变化" }
            val pageUrls = first.select(".pt-dir-sel a[href]").map { mobile(it.absUrl("href")) }
                .mapNotNull { u -> Regex("[?&]page=(\\d+)").find(u)?.groupValues?.get(1)?.toIntOrNull()?.let { it to u } }
                .filter { it.first > 1 }.distinctBy { it.first }.sortedBy { it.first }
            val loaded = chapterStore.load(normalizedBook, count, firstEpisodes, pageUrls, loadFullPages, cancelled,
                { u -> parseEpisodes(doc(u, cancelled)) },
                { page, total -> notifyLoadingEpisodes("$page / $total（慢速加载，切换书籍会停止）") })
            loaded.forEach { episodes[it.url] = it }

        }
        return BookDetail(episodes.values.toList(), intro, artist, author, count, d.select("img.book-cover").first()?.absUrl("src") ?: "")
    }
    internal fun parseEpisodes(d: Document): List<Episode> = d.select("ol.novel-text-list a[href^=/play/]")
        .map { Episode(it.text(), mobile(it.absUrl("href"))) }.distinctBy { it.url }
    override fun getAudioUrlExtractor(): AudioUrlExtractor {
        AudioUrlCustomExtractor.setUp { resolveAudio(it) }
        return AudioUrlCustomExtractor
    }
    internal fun resolveAudio(url: String): String {
        val normalized = mobile(url)
        val d = doc(normalized)
        fun meta(name: String) = d.select("meta[name=$name]").attr("content").also { check(it.isNotEmpty()) { "缺少播放参数 $name" } }
        val sc = meta("_c")
        val json = JSONObject(SiteHttp.request("$BASE/api/mapi/play",
            listOf("nid" to meta("_b"), "cid" to meta("_p"), "sort" to meta("_d")),
            mapOf("sc" to sc, "sp" to signature(sc), "Referer" to normalized)))
        check(json.optInt("status") == 200) { json.optString("msg", "音频解析失败") }
        val audio = json.getString("url")
        require(URI(audio).scheme in listOf("http", "https")) { "无效音频地址" }
        return audio // Re-resolve using the current browser session, including after logout.
    }
    override fun headers(audioUrl: String): Map<String, String> = mapOf("User-Agent" to UA, "Referer" to "https://www.itingshu.net/")
    internal fun signature(sc: String): String {
        val alphabet = "PXhw7U1B0a9kQDKZsTjIASmOeNzxYG4CHo1JyRfg2b8FLpEvr3FtVnlqMidu6c"
        val random = Random()
        return buildString { sc.forEach { c ->
            val i = alphabet.indexOf(c)
            append(alphabet[random.nextInt(62)])
            append(if (i < 0) c else alphabet[(i + 3) % 62])
            append(alphabet[random.nextInt(62)])
        } }
    }
}

// Per-source CookieManager: never change Fuel's global cookie handler or other sources.
internal class SiteSession(
    private val sharedRead: ((String) -> String?)? = null,
    private val sharedWrite: ((String, String) -> Unit)? = null
) {
    init { require((sharedRead == null) == (sharedWrite == null)) }
    val shared: Boolean get() = sharedRead != null
    private val manager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    @Synchronized fun header(url: String): String {
        // In Android use one authoritative store, never overlay stale anonymous cookies.
        if (sharedRead != null) return sharedRead.invoke(url).orEmpty()
        return manager.get(URI(url), emptyMap()).filterKeys { it.equals("Cookie", true) }
            .values.flatten().joinToString("; ")
    }
    @Synchronized fun accept(url: String, headers: Map<String, List<String>>) {
        if (sharedWrite != null) {
            headers.filterKeys { it.equals("Set-Cookie", true) }.values.flatten().forEach {
                sharedWrite.invoke(url, it) // Preserve Domain, Path, Secure, expiry and deletion.
            }
            return
        }
        manager.put(URI(url), headers)
        manager.cookieStore.cookies.forEach { it.version = 0 } // RFC6265 Cookie syntax; no quoted RFC2965 values.
    }
    @Synchronized fun hasToken(): Boolean = header(ITingShu.BASE + "/").split(";").any { it.trim().startsWith("__51guid__=") }
    @Synchronized fun challenge(url: String, html: String): Boolean {
        val encoded = Regex("var reversed\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: return false
        val code = SiteHttp.decodeBase64(encoded.reversed())
        val token = Regex("var token\\s*=\\s*'([^']+)'").find(code)?.groupValues?.get(1) ?: error("站点 Cookie 格式已变化")
        val retry = Regex("var retry\\s*=\\s*(\\d+)").find(code)?.groupValues?.get(1) ?: error("站点重试格式已变化")
        // Reflect the script's final values, then honor later server deletion/rotation.
        accept(url, mapOf("Set-Cookie" to listOf(
            "__51guid__=${URLEncoder.encode(token, "UTF-8")}; Path=/; Max-Age=7200; Secure",
            "__51refresh__guid=$retry; Path=/; Max-Age=3600")))
        return true
    }
}

internal data class SiteReply(val status: Int, val headers: Map<String, List<String>>, val body: String)
private fun fuelExchange(url: String, data: List<Pair<String, String>>?, headers: Map<String, String>): SiteReply {
    val req = if (data == null) Fuel.get(url) else Fuel.post(url, data)
    val (_, response, result) = req.header(headers).timeout(25000).timeoutRead(25000).responseString()
    return SiteReply(response.statusCode, response.headers.mapValues { it.value.toList() },
        if (response.statusCode in 200..299 || response.statusCode <= 0) result.get() else response.data.toString(Charsets.UTF_8))
}
internal open class SiteClient(
    private val exchange: (String, List<Pair<String, String>>?, Map<String, String>) -> SiteReply = ::fuelExchange,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    internal var session: SiteSession = SiteSession()
) {
    @Volatile private var blockedUntil = 0L
    private var lastRequestAt = 0L
    @Volatile private var lastEvent = "尚未请求"
    @Volatile private var requestsSent = 0
    fun status(): String {
        val remaining = ((blockedUntil - clock() + 999L) / 1000L).coerceAtLeast(0L)
        val cookies = if (session.shared) runCatching {
            if (session.header(ITingShu.BASE + "/").isBlank()) "网页 Cookie 为空，请在 APP 内登录" else "已读取网页 Cookie（不代表登录有效）"
        }.getOrDefault("读取网页 Cookie 失败") else "独立测试会话"
        return "r5；$cookies\n已发送 $requestsSent 次请求；等待 $remaining 秒。\n$lastEvent"
    }
    private val cache = object : LinkedHashMap<String, Pair<Long, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, String>>) = size > 256
    }
    @Synchronized fun ensureSession() {
        if (!session.hasToken()) request("${ITingShu.BASE}/api/mapi/play")
    }
    // Serializing the source's requests prevents concurrent challenges from overwriting sessions.
    @Synchronized fun request(url: String, data: List<Pair<String, String>>? = null, extra: Map<String, String> = emptyMap(), cancelled: () -> Boolean = { false }): String {
        if (cancelled()) throw CancellationException()
        val uri = URI(url)
        require(uri.host == "m.itingshu.net")
        val cacheable = !uri.path.startsWith("/play/") && !uri.path.startsWith("/api/")
        // Partition cached pages by current session without retaining cookie text in cache keys.
        val cookieKey = java.security.MessageDigest.getInstance("SHA-256")
            .digest(session.header(url).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val key = url + "|" + (data?.toString() ?: "GET") + "|" + cookieKey
        if (cacheable) {
            val hit = cache[key]
            if (hit != null && (clock() - hit.first < 600000L || (blockedUntil > clock() && clock() - hit.first < 86400000L))) return hit.second
        }
        val remaining = (blockedUntil - clock() + 999L) / 1000L
        check(remaining <= 0L) { "站点限流，还需等待 $remaining 秒；已缓存页面仍可查看" }
        for (attempt in 0..2) {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            val wait = lastRequestAt + 4000L - clock()
            if (wait > 0L) sleeper(wait)
            if (cancelled()) throw CancellationException()
            val headers = mutableMapOf("User-Agent" to ITingShu.UA, "Referer" to "${ITingShu.BASE}/")
            val cookie = session.header(url)
            if (cookie.isNotEmpty()) headers["Cookie"] = cookie
            headers.putAll(extra)
            lastRequestAt = clock()
            requestsSent++
            val response = try { exchange(url, data, headers) } catch (e: Exception) {
                lastEvent = "网络失败：${uri.path}；${e.javaClass.simpleName}"
                throw e
            }
            lastEvent = "HTTP ${response.status}：${uri.path}"
            session.accept(url, response.headers.mapValues { it.value.toList() })
            if (response.status == 429) {
                val value = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()
                val dateDelay = if (value?.toLongOrNull() == null && value != null) runCatching {
                    val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                    ((format.parse(value)!!.time - clock() + 999L) / 1000L).coerceAtLeast(1L)
                }.getOrNull() else null
                val seconds = value?.toLongOrNull()?.coerceIn(1L, 86400L) ?: dateDelay ?: 60L
                blockedUntil = clock() + seconds * 1000L
                error("站点限流，请在 $seconds 秒后重试；已缓存页面仍可查看")
            }
            check(response.status in 200..299) { "站点 HTTP ${response.status}：${uri.path}" }
            if (cancelled()) throw CancellationException()
            val html = response.body
            if (session.challenge(url, html)) continue
            check(!html.contains("<title>Loading...</title>")) { "未知站点验证格式" }
            if (cacheable && !html.contains("抱歉，无相关内容") && html.contains("class=\"book-")) {
                cache[key] = clock() to html
            } else if (cacheable && html.contains("novel-text-list")) {
                cache[key] = clock() to html
            }
            return html
        }
        error("站点 Cookie 验证失败；已停止重试，请稍后再试")
    }
    // Decode data only. Never evaluate JavaScript returned by the site.
    internal fun decodeBase64(s: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        var value = 0; var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (c in s) {
            if (c == '=') break
            val n = alphabet.indexOf(c); require(n >= 0) { "非法 Base64" }
            value = (value shl 6) or n; bits += 6
            if (bits >= 8) { bits -= 8; out.write((value shr bits) and 255) }
        }
        return out.toString("UTF-8")
    }
}


internal object SiteHttp : SiteClient(session = SiteSession(
    sharedRead = { url -> getCookie(url) },
    sharedWrite = { url, value -> android.webkit.CookieManager.getInstance().setCookie(url, value) }
))
