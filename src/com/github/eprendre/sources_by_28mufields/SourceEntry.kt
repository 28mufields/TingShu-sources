package com.github.eprendre.sources_by_28mufields

import com.github.eprendre.tingshu.sources.TingShu

object SourceEntry {
    @JvmStatic
    fun getDesc(): String = "28MUFIELDS 听书源"

    @JvmStatic
    fun getCategory(): String = "听书"

    @JvmStatic
    fun getSources(): List<TingShu> = listOf(
        ITingShu
    )
}
