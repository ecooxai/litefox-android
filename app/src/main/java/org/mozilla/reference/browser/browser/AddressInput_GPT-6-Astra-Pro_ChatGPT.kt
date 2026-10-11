/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import java.net.IDN
import java.net.URI
import java.util.Locale

/** One address-vs-search decision for the URL editor and its preview. */
internal object AddressInput {
    fun urlOrNull(input: String): String? {
        val value = input.trim()
        if (value.isEmpty() || value.any { it.isISOControl() } || value.contains('\\')) return null
        val scheme = value.substringBefore(':', "").lowercase(Locale.ROOT)
        if (scheme in setOf("about", "file", "content", "moz-extension")) return value
        val explicitWebScheme = scheme == "http" || scheme == "https"
        if (!explicitWebScheme && value.any { it.isWhitespace() }) return null
        val candidate = if (explicitWebScheme) value else "https://" + value.removePrefix("//")
        val parsed = runCatching { URI(candidate) }.getOrNull() ?: return null
        val authority = parsed.rawAuthority ?: return null
        // Do not interpret pasted credentials, JavaScript, data, or intents as addresses.
        if (authority.isEmpty() || authority.contains('@')) return null
        if (authority.startsWith('[')) {
            // Android 28 Uri.host mishandles bracketed IPv6. java.net.URI validates it
            // consistently without resolving DNS or making a network request.
            val host = parsed.host ?: return null
            if (!host.startsWith('[') || !host.endsWith(']')) return null
            val suffix = authority.substringAfter(']')
            if (suffix.isNotEmpty() && !validPortSuffix(suffix)) return null
            return candidate
        }
        val host = authority.substringBefore(':')
        val suffix = authority.removePrefix(host)
        if (suffix.isNotEmpty() && !validPortSuffix(suffix)) return null
        val asciiHost = runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES) }
            .getOrNull()?.removeSuffix(".") ?: return null
        if (asciiHost.isEmpty() || asciiHost.length > 253) return null
        val labels = asciiHost.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return null
        // No Android-version-specific TLD allowlist: new and international domains work too.
        val dottedHost = labels.size > 1 && labels.last().any { it.isLetter() }
        val ipv4 = labels.size == 4 && labels.all { part ->
            part.all { it.isDigit() } && part.toIntOrNull()?.let { it in 0..255 } == true
        }
        val localHost = asciiHost.equals("localhost", ignoreCase = true)
        return candidate.takeIf { explicitWebScheme || dottedHost || ipv4 || localHost }
    }

    private fun validPortSuffix(suffix: String): Boolean =
        suffix.startsWith(':') && suffix.drop(1).isNotEmpty() &&
            suffix.drop(1).all { it.isDigit() } &&
            suffix.drop(1).toIntOrNull()?.let { it in 1..65535 } == true

    /** Never promote a previous query's page when the current preview is pending. */
    fun previewTarget(enteredTarget: String, requestedTarget: String?, displayedTarget: String?): String =
        if (enteredTarget == requestedTarget) {
            displayedTarget?.takeIf { urlOrNull(it) != null && it != "about:blank" } ?: enteredTarget
        } else {
            enteredTarget
        }
}
