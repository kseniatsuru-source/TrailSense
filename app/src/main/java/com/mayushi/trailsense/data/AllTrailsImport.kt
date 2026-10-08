package com.mayushi.trailsense.data

import java.net.URI

object AllTrailsImport {
    private val supportedHosts = setOf("alltrails.com", "www.alltrails.com")

    data class SharedRoute(val originalUrl: String, val canonicalUrl: String, val suggestedTitle: String)

    fun parseSharedText(text: String): SharedRoute? {
        val raw = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(text)?.value?.trimEnd('.', ',', ')', ']', '}') ?: return null
        val uri = try { URI(raw) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        if (host !in supportedHosts) return null
        val normalizedPath = (uri.path ?: "/").replace(Regex("/+"), "/").trimEnd('/').ifBlank { "/" }
        val canonical = URI("https", "www.alltrails.com", normalizedPath, null).toString()
        val slug = normalizedPath.substringAfterLast('/').substringBefore('?')
        val title = slug.replace('-', ' ').replace('_', ' ').trim().split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }.ifBlank { "Маршрут AllTrails" }
        return SharedRoute(raw, canonical, title)
    }
}
