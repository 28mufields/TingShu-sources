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
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

object YuetingBa : TingShu() {
    private const val BASE_URL = "http://www.yuetingba.cn"
    private const val STATIC_KEY_B64 = "le95G3hnFDJsBE+1/v9eYw=="
    private const val STATIC_IV_B64 = "IvswQFEUdKYf+d1wKpYLTg=="

    override fun getSourceId(): String = "987f426064bd4063907c67a8fc1064ec"

    override fun getUrl(): String = BASE_URL

    override fun getName(): String = "悦听吧"

    override fun getDesc(): String =
        "悦听吧（yuetingba.cn）\n基于旧源逻辑更新，支持分类、搜索、多页章节和原生音频解析"

    override fun isCacheable(): Boolean = true

    override fun isWebViewNotRequired(): Boolean = true

    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        val encoded = URLEncoder.encode(keywords, "UTF-8")
        val url = "$BASE_URL/search?type=1&name=$encoded&pageIndex=$page"
        val doc = Jsoup.connect(url).config(true).get()
        val books = parseBookList(doc)
        val totalPage = parseTotalPages(doc.text(), page)
        return Pair(books, totalPage)
    }

    override fun getAudioUrlExtractor(): AudioUrlExtractor {
        AudioUrlCustomExtractor.setUp { episodeValue ->
            val parts = episodeValue.split(",", limit = 2)
            require(parts.size == 2) { "悦听吧章节参数格式错误" }

            val serverBase = parts[0]
            val chapterId = parts[1]
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
            val path = decryptBase64WithPlainKey(encrypted, key, iv)

            serverBase.trimEnd('/') + "/" + path.trimStart('/')
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
        val doc = Jsoup.connect(url).config(true).ignoreContentType(true).get()
        val books = parseBookList(doc)

        val currentPage = doc.selectFirst(".pagelist .current")?.text()?.toIntOrNull()
            ?: Regex("/(\\d+)$").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1

        val totalPage = parseTotalPages(doc.text(), currentPage)

        val nextHref = doc.select(".pagelist a, a").firstOrNull {
            it.text().trim() == "下一页" || it.text().contains("下页")
        }?.attr("abs:href").orEmpty()

        val nextUrl = if (nextHref.isNotEmpty()) {
            nextHref
        } else if (currentPage < totalPage) {
            url.replace(Regex("/\\d+$"), "/" + (currentPage + 1))
        } else {
            ""
        }

        return Category(books, currentPage, totalPage, url, nextUrl)
    }

    override fun getBookDetailInfo(
        bookUrl: String,
        loadEpisodes: Boolean,
        loadFullPages: Boolean
    ): BookDetail {
        if (!loadEpisodes) return BookDetail(emptyList())

        val firstDoc = Jsoup.connect(bookUrl).config(true).ignoreContentType(true).get()
        val assl = Regex("""var\\s+assl\\s*=\\s*['"]([^'"]+)['"]""")
            .find(firstDoc.toString())
            ?.groupValues
            ?.getOrNull(1)
            ?: throw IllegalStateException("悦听吧详情页未找到 assl")

        val serverBase = decryptServerUrl(assl)
        val episodes = ArrayList<Episode>()
        parseEpisodes(firstDoc, serverBase, episodes)

        if (loadFullPages) {
            val total = Regex("""共\\s*(\\d+)\\s*集""")
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
                val pageDoc = Jsoup.connect(pageUrl).config(true).ignoreContentType(true).get()
                parseEpisodes(pageDoc, serverBase, episodes)
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
            val titleEl = item.selectFirst(
                ".box-list-item-text-title a, .box-list-item-text-title, a"
            ) ?: return@forEach

            val linkEl = if (titleEl.tagName() == "a") titleEl else item.selectFirst("a")
                ?: return@forEach

            val title = titleEl.text().trim()
            if (title.isEmpty()) return@forEach

            val bookUrl = linkEl.absUrl("href")
            if (bookUrl.isEmpty()) return@forEach

            val imgEl = item.selectFirst("img")
            val cover = imgEl?.let {
                val raw = it.attr("data-original").ifEmpty { it.attr("data-src") }.ifEmpty { it.attr("src") }
                when {
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("/") -> BASE_URL + raw
                    else -> raw
                }
            }.orEmpty()

            val spans = item.select(".box-list-item-text-autspeaker span")
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
        serverBase: String,
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

            if (title.isNotEmpty() && out.none { it.url == "$serverBase,$id" }) {
                out.add(Episode(title, "$serverBase,$id"))
            }
        }
    }

    private fun parseTotalPages(text: String, currentPage: Int): Int {
        val total = Regex("""共\\s*(\\d+)\\s*条""")
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

    private fun decryptServerUrl(assl: String): String {
        val plain = decryptBase64WithEncodedKey(assl, STATIC_KEY_B64, STATIC_IV_B64)
        val array = JSONArray(plain)
        require(array.length() >= 2) { "悦听吧 assl 数据格式异常" }

        val server = array.getJSONObject(1)
        val host = server.getString("Value")
        val port = server.get("Port").toString()

        return "http://$host:$port"
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
}
