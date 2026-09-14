package com.github.eprendre.sources_by_28mufields

import com.github.eprendre.tingshu.extensions.config
import com.github.eprendre.tingshu.extensions.notifyLoadingEpisodes
import com.github.eprendre.tingshu.sources.AudioUrlCustomExtractor
import com.github.eprendre.tingshu.sources.AudioUrlExtractor
import com.github.eprendre.tingshu.sources.TingShu
import com.github.eprendre.tingshu.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

object YuetingBa : TingShu() {
    private const val BASE_URL = "http://www.yuetingba.cn"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
    private const val STATIC_KEY_B64 = "le95G3hnFDJsBE+1/v9eYw=="
    private const val STATIC_IV_B64 = "IvswQFEUdKYf+d1wKpYLTg=="
    private const val AUDIO_SK = "xMiP5W1DHBxC5PwQ5oj5QfRn0tsT5UBk"

    override fun getSourceId(): String = "987f426064bd4063907c67a8fc1064ec"

    override fun getUrl(): String = BASE_URL

    override fun getName(): String = "悦听吧"

    override fun getDesc(): String =
        "悦听吧（yuetingba.cn）\n基于旧源逻辑更新，支持分类、搜索、多页章节和原生音频解析"

    override fun isCacheable(): Boolean = true

    override fun isWebViewNotRequired(): Boolean = true

    override fun isMultipleEpisodePages(): Boolean = true

    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        val encoded = URLEncoder.encode(keywords, "UTF-8")
        val url = "$BASE_URL/search?type=1&name=$encoded&pageIndex=$page"
        val doc = Jsoup.connect(url).config(true)
            .userAgent(UA)
            .referrer(BASE_URL + "/")
            .get()
        val books = parseBookList(doc)
        val totalPage = parseTotalPages(doc.text(), page)
        return Pair(books, totalPage)
    }

    override fun getAudioUrlExtractor(): AudioUrlExtractor {
        AudioUrlCustomExtractor.setUp { episodeValue ->
            val parts = episodeValue.split(",", limit = 5)
            require(parts.size == 5) { "悦听吧章节参数格式错误" }

            val serverUrl = parts[0]
            val serverName = parts[1]
            val py = parts[2]
            val bookId = parts[3]
            val chapterId = parts[4]

            val apiUrl = "$BASE_URL/api/app/docs-listen/$chapterId/ting-with-efi"
            val doc = Jsoup.connect(apiUrl)
                .config(true)
                .ignoreContentType(true)
                .get()

            val obj = JSONObject(doc.text())
            val encrypted = obj.getString("efi")
            val id = obj.getString("id").replace("-", "")
            val creationTime = obj.getString("creationTime")

            val timeDigits = creationTime
                .replace(".", "")
                .replace("-", "")
                .replace("T", "")
                .replace(" ", "")
                .replace(":", "")
                .padEnd(20, '0')

            val key = buildDynamicKey(id, timeDigits)
            val iv = buildDynamicIv(id, timeDigits)
            val decryptedPath = decryptBase64WithPlainKey(encrypted, key, iv)

            val fileName = decryptedPath
                .substringBefore("?")
                .substringAfterLast("/")

            val mediaPath = when {
                serverName.endsWith("_p") ->
                    "/" + py + "_" + bookId + "/" + fileName
                serverName.endsWith("_b") ->
                    "/myfiles/host/listen/booksdir/" + py + "_" + bookId + "/" + fileName
                else -> decryptedPath
            }

            val expire = System.currentTimeMillis() / 1000L + 600L
            val token = md5(fileName + "|" + expire + "|" + AUDIO_SK)
            serverUrl.trimEnd('/') + "/" + mediaPath.trimStart('/') +
                "?token=" + token + "&expire=" + expire
        }
        return AudioUrlCustomExtractor
    }

    override fun getCategoryMenus(): List<CategoryMenu> {
        return listOf(
            CategoryMenu(
                "有声小说",
                listOf(
                    CategoryTab("最新", "$BASE_URL/top/latest/1"),
                    CategoryTab("玄幻", "$BASE_URL/book/1/1"),
                    CategoryTab("历史", "$BASE_URL/book/2/1"),
                    CategoryTab("武侠", "$BASE_URL/book/3/1"),
                    CategoryTab("都市", "$BASE_URL/book/4/1"),
                    CategoryTab("科幻", "$BASE_URL/book/5/1"),
                    CategoryTab("名著", "$BASE_URL/book/6/1"),
                    CategoryTab("女频", "$BASE_URL/book/7/1"),
                    CategoryTab("社科", "$BASE_URL/book/8/1"),
                    CategoryTab("儿童", "$BASE_URL/book/9/1")
                )
            )
        )
    }

    override fun getCategoryList(url: String): Category {
        val doc = Jsoup.connect(url).config(true)
            .userAgent(UA)
            .referrer(BASE_URL + "/")
            .ignoreContentType(true)
            .get()
        val books = parseBookList(doc)

        var currentPage = Regex("/(\\d+)$").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        var totalPage = 1
        var nextUrl = ""

        val pageContainer = doc.selectFirst(".pagelist > div")
        if (pageContainer != null) {
            currentPage = pageContainer.selectFirst(".current")?.text()?.toIntOrNull() ?: currentPage

            val spans = pageContainer.select("span")
            val countText = spans.firstOrNull { it.text().contains("共") && it.text().contains("条") }?.text()
                ?: spans.getOrNull(1)?.text().orEmpty()
            val total = Regex("""共\s*(\d+)\s*条""")
                .find(countText)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            if (total != null) {
                totalPage = maxOf(1, ceil(total / 10.0).toInt())
            }

            val nextHref = pageContainer.select("a").firstOrNull {
                it.text().trim() == "下一页" || it.text().contains("下页")
            }?.attr("href").orEmpty()

            if (nextHref.isNotEmpty()) {
                nextUrl = when {
                    nextHref.startsWith("http://") || nextHref.startsWith("https://") -> nextHref
                    nextHref.startsWith("/") -> BASE_URL + nextHref
                    else -> BASE_URL + "/" + nextHref
                }
            }
        }

        if (totalPage == 1 && nextUrl.isNotEmpty()) {
            totalPage = currentPage + 1
        }

        return Category(books, currentPage, totalPage, url, nextUrl)
    }

    override fun getBookDetailInfo(
        bookUrl: String,
        loadEpisodes: Boolean,
        loadFullPages: Boolean
    ): BookDetail {
        if (!loadEpisodes) return BookDetail(emptyList())

        val firstDoc = Jsoup.connect(bookUrl).config(true)
            .userAgent(UA)
            .referrer(BASE_URL + "/")
            .ignoreContentType(true)
            .get()
        val assl = Regex("""var\s+assl\s*=\s*['"]([^'"]+)['"]""")
            .find(firstDoc.toString())
            ?.groupValues
            ?.getOrNull(1)
            ?: throw IllegalStateException("悦听吧详情页未找到 assl")

        val server = decryptAndSelectServer(assl, bookUrl)
        val py = Regex("""var\s+py\s*=\s*['"]([^'"]+)['"]""")
            .find(firstDoc.toString())
            ?.groupValues
            ?.getOrNull(1)
            ?: ""

        val bookId = Regex("""/book/detail/([^/]+)/""")
            .find(bookUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: throw IllegalStateException("悦听吧书籍地址缺少 bookId")

        val episodes = ArrayList<Episode>()
        parseEpisodes(firstDoc, server.url, server.name, py, bookId, episodes)

        if (loadEpisodes) {
            val total = Regex("""共\s*(\d+)\s*集""")
                .find(firstDoc.text())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: episodes.size

            val totalPages = maxOf(1, ceil(total / 200.0).toInt())
            for (pageIndex in 1 until totalPages) {
                val offset = pageIndex * 200
                notifyLoadingEpisodes((pageIndex + 1).toString() + " / " + totalPages)

                val pageUrl = bookUrl.replace(Regex("/\\d+/?$"), "/" + offset)
                val pageDoc = Jsoup.connect(pageUrl).config(true)
            .userAgent(UA)
            .referrer(BASE_URL + "/")
            .ignoreContentType(true)
            .get()
                parseEpisodes(pageDoc, server.url, server.name, py, bookId, episodes)
            }
            notifyLoadingEpisodes(null)
        }

        return BookDetail(episodes)
    }

    private fun parseBookList(doc: org.jsoup.nodes.Document): List<Book> {
        val list = ArrayList<Book>()
        val elements = doc.select(
            ".col-md-12.col-xs-12.section-box-list-item, " +
                ".section-box-list-item, .box-list-item"
        )

        elements.forEach { item ->
            val linkEl = item.select("a[href*=/book/detail/]").firstOrNull()
                ?: return@forEach
            val titleEl = item.selectFirst(".box-list-item-text-title") ?: linkEl
            val title = titleEl.text().trim()
            if (title.isEmpty()) return@forEach

            val rawHref = linkEl.attr("href")
            val bookUrl = when {
                rawHref.startsWith("http://") || rawHref.startsWith("https://") -> rawHref
                rawHref.startsWith("/") -> BASE_URL + rawHref
                else -> BASE_URL + "/" + rawHref
            }

            val imgEl = item.selectFirst("img")
            val cover = imgEl?.let {
                val raw = it.attr("data-original").ifEmpty { it.attr("data-src") }.ifEmpty { it.attr("src") }
                when {
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("/") -> BASE_URL + raw
                    else -> raw
                }
            }.orEmpty()

            val spans = item.select(".box-list-item-text-autspeaker > span, .box-list-item-text-autspeaker span")
            val author = spans.firstOrNull()?.text()?.trim().orEmpty()
            val artist = spans.lastOrNull()?.text()?.trim().orEmpty()
            val intro = item.selectFirst(
                ".box-list-item-text-intro.text-desc-content, .box-list-item-text-intro, .text-desc-content"
            )?.text()?.trim().orEmpty()

            list.add(Book(cover, bookUrl, title, author, artist).apply {
                sourceId = getSourceId()
                this.intro = intro
            })
        }

        return list
    }

    private fun parseEpisodes(
        doc: org.jsoup.nodes.Document,
        serverUrl: String,
        serverName: String,
        py: String,
        bookId: String,
        out: MutableList<Episode>
    ) {
        val elements = doc.select(
            ".ting-list-content.row [id^=item_], " +
                ".ting-list-content [id^=item_], [id^=item_]"
        )

        elements.forEach { element ->
            val id = element.id().removePrefix("item_")
            if (id.isEmpty()) return@forEach

            val title = element.select("a").lastOrNull()?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: element.text().trim()

            val episodeValue = serverUrl + "," + serverName + "," + py + "," + bookId + "," + id
            if (title.isNotEmpty() && out.none { it.url == episodeValue }) {
                out.add(Episode(title, episodeValue))
            }
        }
    }

    private fun parseTotalPages(text: String, currentPage: Int): Int {
        val total = Regex("""共\s*(\d+)\s*条""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        return if (total != null) {
            maxOf(currentPage, ceil(total / 10.0).toInt())
        } else {
            currentPage
        }
    }

    private data class AudioServer(
        val name: String,
        val url: String
    )

    private fun decryptAndSelectServer(assl: String, bookUrl: String): AudioServer {
        // Current player (v=1.8.5) removes AUDIO_SK from assl before AES decrypting it.
        val cleaned = assl.replace(AUDIO_SK, "")
        val plain = decryptBase64WithEncodedKey(cleaned, STATIC_KEY_B64, STATIC_IV_B64)
        val array = JSONArray(plain)

        val bookId = Regex("""/book/detail/([^/]+)/""")
            .find(bookUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: ""
        val bookSuffix = bookId.substringAfterLast("-")

        val ipv4 = ArrayList<JSONObject>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            if (item.optString("AsType") == "1" && item.optString("Type") == "A") {
                ipv4.add(item)
            }
        }
        require(ipv4.isNotEmpty()) { "悦听吧没有可用 IPv4 音频服务器" }

        val matched = ipv4.filter {
            val ids = it.optString("BookIds")
            ids.isNotEmpty() && ids.split(",").contains(bookSuffix)
        }

        val candidates = if (matched.isNotEmpty()) {
            matched
        } else {
            ipv4.filter { it.isNull("BookIds") || it.optString("BookIds").isEmpty() }
                .ifEmpty { ipv4 }
        }

        // Browser code uses weighted random selection. Deterministic highest-ratio selection
        // is more stable for an external source and uses the same eligible candidate set.
        val selected = candidates.maxByOrNull { it.optInt("Ratio", 0) } ?: candidates.first()
        val scheme = selected.optString("Scheme", "http")
        val host = selected.getString("Value")
        val port = selected.get("Port").toString()

        return AudioServer(
            name = selected.optString("Name"),
            url = scheme + "://" + host + ":" + port
        )
    }

    private fun decryptBase64WithEncodedKey(
        encryptedBase64: String,
        keyBase64: String,
        ivBase64: String
    ): String {
        val key = Base64.decode(keyBase64, Base64.DEFAULT)
        val iv = Base64.decode(ivBase64, Base64.DEFAULT)
        return decryptAesCbc(encryptedBase64, key, iv)
    }

    private fun decryptBase64WithPlainKey(
        encryptedBase64: String,
        key: String,
        iv: String
    ): String {
        return decryptAesCbc(
            encryptedBase64,
            key.toByteArray(StandardCharsets.UTF_8),
            iv.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun decryptAesCbc(
        encryptedBase64: String,
        key: ByteArray,
        iv: ByteArray
    ): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        val encrypted = Base64.decode(encryptedBase64, Base64.DEFAULT)
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private fun buildDynamicKey(e: String, t: String): String {
        require(e.length >= 32 && t.length >= 20) { "悦听吧动态密钥参数异常" }
        val n = StringBuilder()
        for (i in 0 until 20) {
            n.append((e[i].code + t[i].digitToInt()).toChar())
        }
        for (i in 20 until e.length) {
            n.append((e[i].code + t[i - 20].digitToInt()).toChar())
        }
        return n.toString()
    }

    private fun buildDynamicIv(e: String, t: String): String {
        require(e.length > 20 && t.length >= 20) { "悦听吧动态 IV 参数异常" }
        val n = StringBuilder()
        for (i in 20 downTo 5) {
            n.append((e[i].code + t[i - 1].digitToInt()).toChar())
        }
        return n.toString()
    }
    private fun md5(value: String): String {
        val bytes = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

}
