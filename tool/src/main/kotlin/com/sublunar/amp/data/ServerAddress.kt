package com.sublunar.amp.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What to try for a server address as it was typed.
 *
 * With http:// or https:// in front, exactly that. Without, https:// first —
 * and plain http:// after it only for an address on the user's own network.
 * There, http:// is usually all a server speaks, and reading it means being on
 * that network already. A public name gets https:// alone: falling back there
 * would hand the password to anyone on the way who blocks https://, and
 * Jellyfin sends it as typed.
 *
 * Judged by the spelling, never by where the name resolves. A public name that
 * a home DNS points at a LAN address still has a certificate to offer.
 */
object ServerAddress {

    /** The spellings of [raw] to try, in order, without a trailing slash. */
    fun candidates(raw: String): List<String> {
        val address = raw.trim().trimEnd('/')
        if (SCHEME.containsMatchIn(address)) return listOf(address)
        val https = "https://$address"
        val host = https.toHttpUrlOrNull()?.host
        return if (host != null && isLocal(host)) listOf(https, "http://$address") else listOf(https)
    }

    /** Whether [host] belongs to the user's own network, by its spelling alone — nothing is looked up. */
    internal fun isLocal(host: String): Boolean {
        val name = host.lowercase().removeSurrounding("[", "]").trimEnd('.')
        ipv4(name)?.let { return isLocalV4(it) }
        if (':' in name) return isLocalV6(name)
        return '.' !in name || LOCAL_SUFFIXES.any { name.endsWith(it) }
    }

    /**
     * Whether the server at [url] is on the network the phone is on — the one
     * case where streaming a song instead of playing its download is quick as
     * well as free. Judged by spelling, like [isLocal], and a plex.direct name
     * by the address it spells: `192-168-1-5.<hash>.plex.direct` resolves to
     * 192.168.1.5.
     *
     * Tailscale's range is not counted, though [isLocal] counts it: its tunnel
     * crosses whatever network the phone is on, hotel Wi-Fi included.
     */
    fun isOnLan(url: String): Boolean {
        val host = url.trim().toHttpUrlOrNull()?.host?.lowercase() ?: return false
        val spelled = if (host.endsWith(".plex.direct")) host.substringBefore('.').replace('-', '.') else host
        ipv4(spelled)?.let { (a, b) -> if (a == 100 && b in 64..127) return false }
        if (spelled != host) return ipv4(spelled)?.let(::isLocalV4) == true
        return isLocal(spelled)
    }

    private fun ipv4(name: String): List<Int>? {
        val parts = name.split('.')
        if (parts.size != 4) return null
        return parts.map { part -> part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null }
    }

    private fun isLocalV4(ip: List<Int>): Boolean {
        val (a, b) = ip
        return a == 10 ||
            a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            // Tailscale hands out these, and its tunnel is encrypted already.
            (a == 100 && b in 64..127)
    }

    private fun isLocalV6(ip: String): Boolean {
        if (ip == "::1") return true
        val first = ip.substringBefore(':').ifEmpty { return false }.toIntOrNull(16) ?: return false
        return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80
    }

    private val SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)

    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal")
}
