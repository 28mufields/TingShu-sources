package com.github.eprendre.sources_by_28mufields

import com.github.eprendre.tingshu.extensions.config
import com.github.eprendre.tingshu.extensions.notifyLoadingEpisodes
import com.github.eprendre.tingshu.sources.AudioUrlExtractor
import com.github.eprendre.tingshu.sources.AudioUrlCustomExtractor
import com.github.eprendre.tingshu.sources.AudioUrlExtraHeaders
import com.github.eprendre.tingshu.sources.CoverUrlExtraHeaders
import com.github.eprendre.tingshu.sources.TingShu
import com.github.eprendre.tingshu.utils.*
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URI
import kotlin.random.Random

object ITingShu : TingShu(), AudioUrlExtraHeaders, CoverUrlExtraHeaders {
    private val pageList = ArrayList<String>()
    private val fullChapterCache = LinkedHashMap<String, List<Episode>>()

    // 保留当前正在使用的爱听书 sourceId，避免历史记录/收藏关联失效。
    override fun getSourceId(): String = "3aa11119c74448efbd26cd3d16038bbc"

    override fun getUrl(): String = "https://www.itingshu.net"

    override fun getName(): String = "爱听书"

    override fun getDesc(): String = "推荐指数:5星 ⭐⭐⭐⭐⭐\n爱听书（搜索与播放修复版）"

    override fun isMultipleEpisodePages(): Boolean = true

    override fun isSearchable(): Boolean = true

    override fun isDiscoverable(): Boolean = true

    override fun reset() {
        pageList.clear()
    }

    override fun getAudioUrlExtractor(): AudioUrlExtractor {
        AudioUrlCustomExtractor.setUp { episodeUrl ->
            resolveAudio(episodeUrl)
        }
        return AudioUrlCustomExtractor
    }

    private fun mobile(url: String): String {
        val uri = URI("https://m.itingshu.net").resolve(url)
        require(uri.scheme == "https" || uri.scheme == "http") { "非本站链接" }
        require(uri.host == "m.itingshu.net" || uri.host == "www.itingshu.net") { "非本站链接" }
        val query = uri.rawQuery?.let { "?" + it } ?: ""
        return "https://m.itingshu.net" + uri.rawPath + query
    }

    private fun mobileDoc(url: String): org.jsoup.nodes.Document {
        val normalized = mobile(url)
        return Jsoup.parse(ITingShuHttp.request(normalized), normalized)
    }

    private fun resolveAudio(url: String): String {
        val normalized = mobile(url)
        ITingShuAudioCache.get(normalized)?.let { return it }

        val d = mobileDoc(normalized)
        fun meta(name: String): String {
            val value = d.select("meta[name=" + name + "]").attr("content")
            require(value.isNotEmpty()) { "缺少播放参数 " + name }
            return value
        }

        val sc = meta("_c")
        val body = ITingShuHttp.request(
            "https://m.itingshu.net/api/mapi/play",
            data = listOf(
                "nid" to meta("_b"),
                "cid" to meta("_p"),
                "sort" to meta("_d")
            ),
            extra = mapOf(
                "sc" to sc,
                "sp" to signature(sc),
                "Referer" to normalized,
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to "https://m.itingshu.net"
            )
        )

        val json = JSONObject(body)
        require(json.optInt("status") == 200) {
            json.optString("msg", "音频解析失败")
        }

        val audio = json.getString("url")
        require(URI(audio).scheme == "http" || URI(audio).scheme == "https") { "无效音频地址" }
        ITingShuAudioCache.put(normalized, audio)
        return audio
    }

    private fun signature(sc: String): String {
        val table = "PXhw7U1B0a9kQDKZsTjIASmOeNzxYG4CHo1JyRfg2b8FLpEvr3FtVnlqMidu6c"
        return buildString(sc.length * 3) {
            for (ch in sc) {
                val i = table.indexOf(ch)
                append(table[Random.nextInt(62)])
                append(if (i < 0) ch else table[(i + 3) % 62])
                append(table[Random.nextInt(62)])
            }
        }
    }

    override fun headers(audioUrl: String): Map<String, String> =
        mapOf(
            "User-Agent" to ITingShuHttp.UA,
            "Referer" to "https://www.itingshu.net/"
        )

    override fun getCategoryMenus(): List<CategoryMenu> {
        val list1 = ArrayList<CategoryTab>()
        try {
            val doc = Jsoup.connect("https://www.itingshu.net/yousheng/all.html").config(true).get()
            val navs = doc.select(".top-ul > li").firstOrNull()?.select("dl > dd") ?: emptyList()
            navs.forEach { li ->
                val title = li.selectFirst("a")?.text() ?: ""
                val href = li.selectFirst("a")?.absUrl("href") ?: ""
                if (title.isNotEmpty() && href.isNotEmpty()) {
                    list1.add(CategoryTab(title, href))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val list2 = ArrayList<CategoryTab>()
        try {
            val doc2 = Jsoup.connect("https://www.itingshu.net/boyin/485/").config(true).get()
            val navs2 = doc2.select(".module-tab h3")
            navs2.forEach { li ->
                val title = li.selectFirst("a")?.text() ?: ""
                val href = li.selectFirst("a")?.absUrl("href") ?: ""
                if (title.isNotEmpty() && href.isNotEmpty()) {
                    list2.add(CategoryTab(title, href))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return listOf(CategoryMenu("有声小说", list1), CategoryMenu("长篇评书", list2))
    }

    override fun getCategoryList(url: String): Category {
        val doc = Jsoup.connect(url).config(true).get()
        val list = ArrayList<Book>()

        val elements = doc.select(".list-works li, div.list_item")
        elements.forEach { li ->
            val titleEl = li.selectFirst(".list-works-dl > .list-book-dt > a, h3.title a, .title a") ?: return@forEach
            val title = titleEl.text().trim()
            val href = titleEl.absUrl("href")
            val status = li.selectFirst(".list-works-dl > .list-book-dt > span, .tag-small-group em, span.status")?.text()?.trim() ?: ""
            val img = li.selectFirst(".list-imgbox img, img")?.let {
                val src = it.attr("data-original").ifEmpty { it.attr("src") }
                if (src.startsWith("//")) "https:$src" else src
            } ?: ""
            val author = li.selectFirst(".book-author, p.author")?.text()?.replace("作者：", "")?.trim() ?: ""
            val artist = li.selectFirst(".book-boyin, .book-artist, p.actor")?.text()?.replace("演播：", "")?.trim() ?: ""
            val intro = li.selectFirst(".list-book-des, p.desc")?.text()?.trim() ?: ""

            list.add(Book(img, href, title, author, artist).apply {
                this.sourceId = getSourceId()
                this.intro = intro
                this.status = status
            })
        }

        val nextUrl = doc.select(".fanye a, .page a").firstOrNull {
            it.text().contains("下页") || it.text().contains("下一页")
        }?.attr("abs:href") ?: ""
        val currentPage = Regex("/(\\d+)\\.html").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val totalPage = doc.selectFirst(".fanye")?.children()
            ?.map { it.ownText() }
            ?.filter { it.matches("\\d+".toRegex()) }
            ?.lastOrNull()?.toIntOrNull() ?: if (nextUrl.isNotEmpty()) currentPage + 1 else currentPage

        return Category(list, currentPage, totalPage, url, nextUrl)
    }

    override fun getBookDetailInfo(
        bookUrl: String,
        loadEpisodes: Boolean,
        loadFullPages: Boolean
    ): BookDetail {
        val normalizedBook = mobile(bookUrl)
        val d = mobileDoc(normalizedBook)

        require(d.selectFirst("#book-detail[data-bid]") != null) {
            "详情页结构已变化"
        }

        val author = d.select(".book-detail-info a[href^=/author/]").text()
        val artist = d.select(".book-detail-info a[href^=/boyin/]")
            .eachText().joinToString("，")
        val intro = d.select(".cor-introduce").text()
        val count = Regex("""共\s*(\d+)\s*集""")
            .find(d.text())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val cover = d.selectFirst("img.book-cover")?.absUrl("src").orEmpty()

        if (!loadEpisodes) {
            return BookDetail(emptyList(), intro, artist, author, count, cover)
        }

        fullChapterCache[normalizedBook]?.let { cached ->
            if (count <= 0 || cached.size >= count) {
                return BookDetail(cached, intro, artist, author, count, cover)
            }
        }

        val catalogLink = d.select("a[href]").firstOrNull {
            val text = it.text()
            text.contains("查看完整目录") ||
                text.contains("查看全部章节") ||
                text.contains("全部章节")
        } ?: throw IllegalStateException("未找到完整目录")

        val catalogUrl = mobile(catalogLink.absUrl("href"))
        val first = mobileDoc(catalogUrl)
        val all = LinkedHashMap<String, Episode>()

        parseEpisodes(first).forEach { all[it.url] = it }
        require(all.isNotEmpty()) { "目录为空或结构已变化" }

        val pages = first.select(".pt-dir-sel a[href]")
            .mapNotNull {
                val u = mobile(it.absUrl("href"))
                val page = Regex("""[?&]page=(\d+)""")
                    .find(u)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (page != null && page > 1) page to u else null
            }
            .distinctBy { it.first }
            .sortedBy { it.first }

        // 旧可用版会主动把完整目录的 50 集分页逐页拉完。
        // 这里不依赖宿主第二次刷新，第一次打开书籍就加载完整章节。
        val totalPages = pages.size + 1
        for ((index, pair) in pages.withIndex()) {
            notifyLoadingEpisodes(
                (index + 2).toString() + " / " + totalPages + "（慢速加载）"
            )
            parseEpisodes(mobileDoc(pair.second)).forEach { all[it.url] = it }
        }
        notifyLoadingEpisodes(null)

        val episodes = all.values.toList()
        fullChapterCache[normalizedBook] = episodes
        return BookDetail(episodes, intro, artist, author, count, cover)
    }

    private fun parseEpisodes(d: org.jsoup.nodes.Document): List<Episode> =
        d.select("ol.novel-text-list a[href^=/play/]")
            .map {
                Episode(it.text().trim(), mobile(it.absUrl("href")))
            }
            .distinctBy { it.url }

    override fun search(keywords: String, page: Int): Pair<List<Book>, Int> {
        val searchUrl = "https://www.itingshu.net/novelsearch/search/result.html"
        val doc = Jsoup.connect(searchUrl).config(true)
            .data(mapOf(
                "searchword" to keywords,
                "searchtype" to "0",
                "page" to page.toString()
            ))
            .header("Referer", "https://www.itingshu.net/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .timeout(15000)
            .post()

        val list = ArrayList<Book>()
        val elements = doc.select(".list-works li, div.search-list div.list_item, div.list_item")
        elements.forEach { li ->
            val titleEl = li.selectFirst(".list-works-dl > .list-book-dt > a, .list-book-dt a, h3.title a, .title a") ?: return@forEach
            val title = titleEl.text().trim()
            val href = titleEl.absUrl("href")
            val status = li.selectFirst(".list-works-dl > .list-book-dt > span, .list-book-dt span, span.status")?.text()?.trim() ?: ""
            val img = li.selectFirst(".list-imgbox img, img")?.let {
                val src = it.attr("data-original").ifEmpty { it.attr("src") }
                if (src.startsWith("//")) "https:$src" else src
            } ?: ""
            val author = li.selectFirst(".book-author, p.author")?.text()?.replace("作者：", "")?.trim() ?: ""
            val artist = li.selectFirst(".book-boyin, .book-artist, p.actor")?.text()?.replace("演播：", "")?.trim() ?: ""
            val intro = li.selectFirst(".list-book-des, p.desc")?.text()?.trim() ?: ""

            list.add(Book(img, href, title, author, artist).apply {
                this.sourceId = getSourceId()
                this.intro = intro
                this.status = status
            })
        }

        val nextUrl = doc.select(".fanye a, .page a").firstOrNull {
            it.text().contains("下页") || it.text().contains("下一页")
        }?.attr("abs:href") ?: ""
        val totalPage = doc.selectFirst(".fanye")?.children()
            ?.map { it.ownText() }
            ?.filter { it.matches("\\d+".toRegex()) }
            ?.lastOrNull()?.toIntOrNull() ?: if (nextUrl.isNotEmpty()) page + 1 else page

        return Pair(list, totalPage)
    }

    override fun coverHeaders(coverUrl: String, headers: MutableMap<String, String>): Boolean {
        if (coverUrl.contains("itingshu.net")) {
            headers["referer"] = "https://www.itingshu.net"
            return true
        }
        return false
    }
}
