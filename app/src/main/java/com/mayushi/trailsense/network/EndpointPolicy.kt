package com.mayushi.trailsense.network

import java.net.InetAddress
import java.net.URI

object EndpointPolicy {
    fun normalizeAndValidate(raw: String): String {
        val value = raw.trim().trimEnd('/')
        require(value.isNotBlank()) { "Укажи адрес Hermes" }
        val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Некорректный адрес Hermes") }
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "Адрес должен начинаться с http:// или https://" }
        if (uri.scheme == "http") require(isPrivateHost(uri.host)) { "Без HTTPS разрешён только локальный или Tailscale-адрес" }
        return value
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".ts.net")) return true
        return try {
            val bytes = InetAddress.getByName(h).address
            when {
                bytes.size == 4 -> {
                    val a = bytes[0].toInt() and 255; val b = bytes[1].toInt() and 255
                    a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127) || (a == 169 && b == 254)
                }
                bytes.size == 16 -> (bytes[0].toInt() and 0xfe) == 0xfc || (bytes[0].toInt() and 0xff) == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80 || bytes.dropLast(1).all { it.toInt() == 0 } && bytes.last().toInt() == 1
                else -> false
            }
        } catch (_: Exception) { false }
    }
}
