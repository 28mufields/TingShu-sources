package com.github.eprendre.sources_by_28mufields

import com.github.eprendre.tingshu.extensions.loadingEvents
import com.github.eprendre.tingshu.utils.Episode
import java.util.concurrent.CancellationException
import org.jsoup.Jsoup

private fun episodes(start: Int, end: Int) = (start..end).map {
    Episode("chapter $it", "https://m.itingshu.net/play/123_1_$it.html")
}

fun main() {
    val base = ITingShu.BASE
    val browser = SiteSession()
    val bridge = SiteSession({ browser.header(it) }, { u, v -> browser.accept(u, mapOf("Set-Cookie" to listOf(v))) })
    val url = "$base/play/123_1_1.html"
    browser.accept(url, mapOf("Set-Cookie" to listOf("auth=first; Path=/; Secure; HttpOnly")))
    val reads = mutableListOf<String>()
    var now = 1000000L
    val client = SiteClient(exchange = { _, _, headers ->
        reads.add(headers["Cookie"].orEmpty())
        SiteReply(200, mapOf("Set-Cookie" to listOf("auth=rotated; Path=/; Secure; HttpOnly")), "ok")
    }, clock = { now }, sleeper = { now += it }, session = bridge)
    client.request(url); client.request(url)
    check(reads[0].contains("auth=first") && reads[1].contains("auth=rotated"))
    browser.accept(url, mapOf("Set-Cookie" to listOf("auth=deleted; Path=/; Max-Age=0")))
    check(!bridge.header(url).contains("auth="))
    check(bridge.header("https://cdn.example/audio.m4a").isEmpty())
    check(ITingShu.headers("https://cdn.example/audio.m4a").keys.none { it.equals("Cookie", true) })
    println("PASS: shared session, rotation, deletion, host isolation, no playback cache")

    val script = "var token = 'test-token'; var retry = 1;"
    val challenge = "var reversed = \"" + java.util.Base64.getEncoder().encodeToString(script.toByteArray()).reversed() + "\""
    var attempts = 0
    val retryClient = SiteClient(exchange = { _, _, headers ->
        attempts++
        if (attempts == 1) SiteReply(200, emptyMap(), challenge)
        else {
            check(headers["Cookie"].orEmpty().contains("__51guid__=test-token"))
            SiteReply(200, emptyMap(), "passed")
        }
    }, clock = { now }, sleeper = { now += it })
    check(retryClient.request(url) == "passed" && attempts == 2)
    println("PASS: challenge retry rereads updated Cookie")

    var calls = 0
    val limited = SiteClient(exchange = { u, _, _ ->
        calls++
        if (u.endsWith("/book/")) SiteReply(200, emptyMap(), "<div class=\"book-test\">cached</div>")
        else SiteReply(429, mapOf("Retry-After" to listOf("3600")), "")
    }, clock = { now }, sleeper = { now += it })
    val page = limited.request("$base/book/")
    check(runCatching { limited.request(url) }.exceptionOrNull()?.message?.contains("3600") == true)
    now += 1200000L
    check(limited.request("$base/book/") == page && calls == 2)
    check(runCatching { limited.request("$base/api/mapi/play") }.isFailure && calls == 2)
    val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
    date.timeZone = java.util.TimeZone.getTimeZone("GMT")
    val dated = SiteClient(exchange = { _, _, _ -> SiteReply(429, mapOf("retry-after" to listOf(date.format(java.util.Date(now + 120000L)))), "") }, clock = { now }, sleeper = { now += it })
    check(runCatching { dated.request(url) }.exceptionOrNull()?.message?.contains("120") == true)
    println("PASS: numeric/date Retry-After, no retry storm, cached browsing during cooldown")

    val store = ChapterStore(clock = { now }, sleeper = { now += it })
    val fetched = mutableListOf<String>()
    var fail = true
    val fetch: (String) -> List<Episode> = { u ->
        fetched.add(u)
        if (u == "third" && fail) error("simulated 429")
        if (u == "second") episodes(50, 100) else episodes(101, 120)
    }
    val pages = listOf(2 to "second", 3 to "third")
    check(store.load("a",120,episodes(1,50),pages,false,{false},fetch,{_,_->}).size == 50 && fetched.isEmpty())
    check(runCatching { store.load("a",120,episodes(1,50),pages,true,{false},fetch,{_,_->}) }.isFailure)
    check(loadingEvents.last() == null)
    now += 3600001L; fail = false
    val full = store.load("a",120,episodes(1,50),pages,true,{false},fetch,{_,_->})
    check(full.map { it.url } == episodes(1,120).map { it.url })
    check(fetched == listOf("second","third","third"))
    check(runCatching { store.load("bad-count",121,episodes(1,50),pages,true,{false},fetch,{_,_->}) }.isFailure)
    var cancel = false; var cancelCalls = 0
    val cancelling = ChapterStore(sleeper = { cancel = true })
    check(runCatching { cancelling.load("b",120,episodes(1,50),pages,true,{cancel},{ cancelCalls++; episodes(51,120) },{_,_->}) }.exceptionOrNull() is CancellationException)
    check(cancelCalls == 0)
    println("PASS: first-page flag, full ordered catalog, chapter deduplication, resume, count check, cancellation")

    // Exercise the real singleton search/detail/playback methods with a synthetic transport.
    // Reflection keeps test injection out of the shipped historical implementation.
    val replacements = mutableListOf<Pair<java.lang.reflect.Field, Any?>>()
    fun replace(name: String, value: Any) {
        val field = SiteClient::class.java.getDeclaredField(name).apply { isAccessible = true }
        replacements.add(field to field.get(SiteHttp)); field.set(SiteHttp, value)
    }
    val originalSession = SiteHttp.session
    val requests = mutableListOf<Pair<String, List<Pair<String,String>>?>>()
    var apiCalls = 0
    val htmlBook: (Int) -> String = { id -> "<ol class='book-ol'><li class='book-li'><a class='book-layout' href='/youshengxiaoshuo/$id/'><img src='/cover.jpg'><h3 class='book-title'>book $id</h3></a></li></ol>" }
    val catalog: (Int,Int) -> String = { a,b -> "<ol class='novel-text-list'>" + (a..b).joinToString("") { "<li><a href='/play/123_1_$it.html'>chapter $it</a></li>" } + "</ol>" }
    try {
        SiteHttp.session = SiteSession().apply { accept(base, mapOf("Set-Cookie" to listOf("__51guid__=test; Path=/; Secure"))) }
        replace("clock", { now }); replace("sleeper", { n: Long -> now += n })
        replace("exchange", { u: String, data: List<Pair<String,String>>?, headers: Map<String,String> ->
            requests.add(u to data)
            val body = when {
                u.contains("/novelsearch/") -> { check(data == listOf("searchword" to "测试")); "<h1 class='novel-title'>搜索</h1>" + htmlBook(1) + "<div class='page'><a href='/search/test/lastupdate/2.html'>2</a></div>" }
                u.contains("/search/") -> { check(u.endsWith("/search/oOoE6oOoB5oOo8BoOoE8oOoAFoOo95/lastupdate/2.html") && data == null); "<h1 class='novel-title'>搜索</h1>" + htmlBook(2) }
                u.contains("/youshengxiaoshuo/") -> "<div id='book-detail' data-bid='123'>共120集</div><a href='/catalog/123'>查看完整目录</a>" + catalog(111,120)
                u.endsWith("?page=2") -> catalog(51,100)
                u.endsWith("?page=3") -> catalog(101,120)
                u.contains("/catalog/") -> catalog(1,50) + "<div class='pt-dir-sel'><a href='/catalog/123?page=3'>3</a><a href='/catalog/123?page=2'>2</a><a href='/catalog/123?page=2'>2</a></div>"
                u.contains("/play/") -> "<meta name='_c' content='abc'><meta name='_b' content='123'><meta name='_p' content='1'><meta name='_d' content='1'>"
                u.contains("/api/") -> {
                    apiCalls++; check(data == listOf("nid" to "123", "cid" to "1", "sort" to "1"))
                    check(headers["sc"] == "abc" && headers["sp"]?.length == 9 && headers["Referer"] == url)
                    "{\"status\":200,\"url\":\"https://cdn.example/$apiCalls.m4a\"}"
                }
                else -> error("Unexpected request: $u")
            }
            SiteReply(200, emptyMap(), body)
        })
        val first = ITingShu.search("测试",1); val second = ITingShu.search("测试",2)
        check(first.second == 2 && first.first.size == 1 && second.first.size == 1)
        check(first.first[0].bookUrl != second.first[0].bookUrl)
        val detail = ITingShu.getBookDetailInfo("$base/youshengxiaoshuo/123/",true,false)
        check(detail.playList.map { it.url } == episodes(1,50).map { it.url })
        val all = ITingShu.getBookDetailInfo("$base/youshengxiaoshuo/123/",true,true)
        check(all.playList.map { it.url } == episodes(1,120).map { it.url })
        check(requests.count { it.first.endsWith("?page=2") } == 1)
        check(ITingShu.resolveAudio(url) == "https://cdn.example/1.m4a")
        check(ITingShu.resolveAudio(url) == "https://cdn.example/2.m4a")
        check(ITingShu.isCacheable() && ITingShu.isWebViewNotRequired())
        check(ITingShu.getSourceId() == "3aa11119c74448efbd26cd3d16038bbc")
        println("PASS: real search pagination, exact full-catalog link, ignore last 10, sorted page URLs, fresh playback API, preserved identity")
    } finally {
        replacements.asReversed().forEach { (f,v) -> f.set(SiteHttp,v) }
        SiteHttp.session = originalSession
        ITingShu.reset()
    }
}
