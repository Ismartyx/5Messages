package com.goodwy.messages.feature.webserver

import android.content.Context
import timber.log.Timber

object WebClientHtml {
    private var cachedHtml: String? = null

    fun getHtml(context: Context): String {
        cachedHtml?.let { return it }
        val html = try {
            context.assets.open("webclient/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Timber.e(e, "Error loading webclient/index.html from assets")
            "<!DOCTYPE html><html><body><h3>5Messages Web</h3><p>Error loading web interface</p></body></html>"
        }
        cachedHtml = html
        return html
    }
}
