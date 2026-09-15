package com.github.eprendre.sources_by_28mufields

import com.github.eprendre.tingshu.utils.Episode
import com.github.eprendre.tingshu.extensions.notifyLoadingEpisodes
import java.util.concurrent.CancellationException

// The host has no partial-success flag in BookDetail. Retain pages on failure, but
// throw rather than mislabeling a truncated book as complete. A later load resumes.
internal class ChapterStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    private data class Page(val at: Long, val episodes: List<Episode>)
    private class BookPages { val pages = mutableMapOf<Int, Page>(); var count = -1; var first = "" }
    private val books = object : LinkedHashMap<String, BookPages>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BookPages>) = size > 8
    }
    fun load(book: String, count: Int, first: List<Episode>, urls: List<Pair<Int, String>>, full: Boolean,
        cancelled: () -> Boolean, fetch: (String) -> List<Episode>, progress: (Int, Int) -> Unit): List<Episode> {
        if (cancelled()) throw CancellationException()
        if (!full) return first
        val entry = synchronized(books) { books.getOrPut(book) { BookPages() } }
        synchronized(entry) {
            fun checkActive() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException() }
            checkActive()
            val fingerprint = first.joinToString("|") { it.url }
            if (entry.count != count || entry.first != fingerprint) entry.pages.clear()
            entry.count = count; entry.first = fingerprint; entry.pages[1] = Page(clock(), first)
            try {
                val result = linkedMapOf<String, Episode>()
                first.forEach { result[it.url] = it }
                for ((n, url) in urls) {
                    checkActive()
                    val cached = entry.pages[n]
                    val page = if (cached != null && clock() - cached.at < 86400000L) cached.episodes else {
                        progress(n, (urls.maxOfOrNull { it.first } ?: 1))
                        // Yield between pages. Check cancellation during the wait and before HTTP.
                        repeat(40) { checkActive(); sleeper(200L) }
                        checkActive()
                        fetch(url).also {
                            check(it.isNotEmpty()) { "第 $n 页目录为空" }
                            entry.pages[n] = Page(clock(), it)
                        }
                    }
                    page.forEach { result[it.url] = it }
                }
                check(count == 0 || result.size == count) { "目录不完整：${result.size}/$count；已取得的页暂存，稍后重试可复用" }
                return result.values.toList()
            } finally { notifyLoadingEpisodes(null) }
        }
    }
}

internal class AudioCache(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val values = object : LinkedHashMap<String, Pair<Long, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, String>>) = size > 16
    }
    @Synchronized fun get(page: String): String? = values[page]?.takeIf { clock() - it.first < 120000L }?.second
    @Synchronized fun put(page: String, audio: String) { values[page] = clock() to audio }
}

