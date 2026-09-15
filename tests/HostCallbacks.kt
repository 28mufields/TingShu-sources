@file:JvmName("MyExtKt")
package com.github.eprendre.tingshu.extensions

// Test-only Android UI callbacks. This file must never enter the source JAR.
val loadingEvents = mutableListOf<String?>()
fun notifyLoadingEpisodes(pageInfo: String?) { loadingEvents.add(pageInfo) }
fun showToast(msg: String) { }
fun getCookie(url: String): String? = error("Tests must inject their own cookie session")
fun getMobileUA(): String = "test-only-user-agent"
