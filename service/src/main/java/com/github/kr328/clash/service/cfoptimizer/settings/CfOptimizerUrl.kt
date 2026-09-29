package com.github.kr328.clash.service.cfoptimizer.settings

import java.net.URI

/**
 * HTTPS origin validation / normalization for the Worker base URL setting.
 * Pure logic — JVM-testable.
 *
 * Rules (deliberately strict, see INTERFACES.md B):
 * - only https scheme
 * - host required, no userinfo, no query, no fragment
 * - no CR/LF (header injection surface)
 * - only default port 443 (explicit port other than 443 rejected)
 * - path (if any) preserved, trailing slash stripped
 */
object CfOptimizerUrl {
    fun validateHttpsOrigin(url: String): String? {
        val trimmed = url.trim()

        if (trimmed.isEmpty()) return null
        if (trimmed.any { it == '\r' || it == '\n' }) return null

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return null
        }

        if (uri.scheme?.lowercase() != "https") return null
        if (uri.host.isNullOrBlank()) return null
        if (!uri.userInfo.isNullOrEmpty()) return null
        if (uri.rawQuery != null || uri.fragment != null) return null
        if (uri.port != -1 && uri.port != 443) return null

        val host = uri.host.lowercase()
        val path = uri.rawPath ?: ""

        return buildString {
            append("https://")
            append(host)
            append(path.trimEnd('/'))
        }
    }
}
