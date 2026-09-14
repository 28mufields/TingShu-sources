package com.github.eprendre.sources_by_28mufields

import com.github.eprendre.tingshu.extensions.config
import com.github.eprendre.tingshu.extensions.notifyLoadingEpisodes
import com.github.eprendre.tingshu.sources.AudioUrlExtractor
import com.github.eprendre.tingshu.sources.AudioUrlWebViewExtractor
import com.github.eprendre.tingshu.sources.CoverUrlExtraHeaders
import com.github.eprendre.tingshu.sources.TingShu
import com.github.eprendre.tingshu.utils.*
import org.jsoup.Jsoup
import kotlin.random.Random

object ITingShu : TingShu(), CoverUrlExtraHeaders {
    private val pageList = ArrayList<String>()

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
        // 移动播放页已经能在宿主“查看源网页”中正常播放。
        // 用 WebView 渲染同一页面，再直接读取播放器 audio 的真实 src；
        // 若挑战页/播放器初始化尚未完成，宿主会在返回 null 后自动重试。
        val script = """
            (function() {
                try {
                    var a = document.querySelector('audio');
                    if (a) {
                        var u = a.currentSrc || a.src || '';
                        if (u) return u;
                        var s = a.querySelector('source');
                        if (s && s.src) return s.src;
                    }
                    var els = document.querySelectorAll('[src]');
                    for (var i = 0; i < els.length; i++) {
                        var u2 = els[i].src || '';
                        if (/\\.(m4a|mp3|aac|m4b|ogg|wav)(\\?|$)/i.test(u2)) return u2;
                    }
                } catch (e) {}
                return '';
            })();
        """.trimIndent()

        AudioUrlWebViewExtractor.setUp(false, script) { result ->
            result
                .trim()
                .trim('"')
                .replace("\\/", "/")
                .takeIf {
                    it.startsWith("http://") || it.startsWith("https://")
                }
        }
        return AudioUrlWebViewExtractor
    }

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

    override fun getBookDetailInfo(bookUrl: String, loadEpisodes: Boolean, loadFullPages: Boolean): BookDetail {
        val list = ArrayList<Episode>()
        if (loadEpisodes) {
            val doc = Jsoup.connect(bookUrl).config(true).get()
            doc.getElementById("playlist")?.select("ul > li")?.forEach { li ->
                val a = li.selectFirst("a") ?: return@forEach
                val playUrl = a.absUrl("href")
                    .replace("https://www.itingshu.net", "https://m.itingshu.net")
                    .replace("http://www.itingshu.net", "https://m.itingshu.net")
                list.add(Episode(a.text().trim(), playUrl))
            }

            if (loadFullPages) {
                val pages = doc.select(".hd-sel > select > option").map { it.absUrl("value") }
                val totalPage = pages.size
                if (pages.size > 1) {
                    pageList.clear()
                    pageList.addAll(pages.drop(1))
                    var page = 1
                    while (pageList.isNotEmpty()) {
                        page++
                        val nextUrl = pageList.removeAt(0)
                        notifyLoadingEpisodes("$page / $totalPage")
                        try {
                            val nextDoc = Jsoup.connect(nextUrl).config(true).get()
                            nextDoc.getElementById("playlist")?.select("ul > li")?.forEach { li ->
                                val a = li.selectFirst("a") ?: return@forEach
                                val playUrl = a.absUrl("href")
                                    .replace("https://www.itingshu.net", "https://m.itingshu.net")
                                    .replace("http://www.itingshu.net", "https://m.itingshu.net")
                                list.add(Episode(a.text().trim(), playUrl))
                            }
                            Thread.sleep(Random.nextLong(100L, 300L))
                        } catch (_: Exception) {
                            break
                        }
                    }
                    notifyLoadingEpisodes(null)
                }
            }
        }
        return BookDetail(list)
    }

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
