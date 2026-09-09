package com.example.animetv.adblock

/**
 * Minimal EasyList/AdGuard-inspired filter list parser and host-matching helpers.
 *
 * Supported rule syntax (one rule per line):
 *   ! comment                 -> ignored
 *   ||domain^                 -> block this domain and all its subdomains
 *   @@||domain^                -> allowlist exception for this domain and subdomains (overrides blocks)
 *   ##selector                 -> cosmetic filter: hide elements matching this CSS selector on every page
 *   anything else               -> treated as a literal substring match against the full request URL
 */
object AdFilterEngine {

    data class ParsedRules(
        val blockedDomains: Set<String>,
        val allowedDomains: Set<String>,
        val blockedSubstrings: List<String>,
        val cosmeticSelectors: List<String>
    ) {
        companion object {
            val EMPTY = ParsedRules(emptySet(), emptySet(), emptyList(), emptyList())
        }
    }

    fun parse(text: String): ParsedRules {
        val blockedDomains = mutableSetOf<String>()
        val allowedDomains = mutableSetOf<String>()
        val blockedSubstrings = mutableListOf<String>()
        val cosmeticSelectors = mutableListOf<String>()

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("!")) return@forEach

            when {
                line.startsWith("@@||") -> {
                    val domain = line.removePrefix("@@||").removeSuffix("^").trim().lowercase()
                    if (domain.isNotEmpty()) allowedDomains.add(domain)
                }
                line.startsWith("||") -> {
                    val domain = line.removePrefix("||").removeSuffix("^").trim().lowercase()
                    if (domain.isNotEmpty()) blockedDomains.add(domain)
                }
                line.startsWith("##") -> {
                    val selector = line.removePrefix("##").trim()
                    if (selector.isNotEmpty()) cosmeticSelectors.add(selector)
                }
                else -> {
                    val pattern = line.lowercase()
                    if (pattern.isNotEmpty()) blockedSubstrings.add(pattern)
                }
            }
        }

        return ParsedRules(blockedDomains, allowedDomains, blockedSubstrings, cosmeticSelectors)
    }

    /** True when [host] is exactly [domain] or a subdomain of it. */
    fun hostMatchesDomain(host: String, domain: String): Boolean {
        return host == domain || host.endsWith(".$domain")
    }

    fun hostMatchesAny(host: String, domains: Collection<String>): Boolean {
        for (domain in domains) {
            if (hostMatchesDomain(host, domain)) return true
        }
        return false
    }

    /**
     * Safer alternative to `host.contains(token)`. Streaming/embed providers frequently rotate
     * across many TLDs and prefixed/suffixed mirror labels (e.g. mixdrop.co / mixdrop.to /
     * mixdrop2.ag / cdn-mixdrop.io), so an exact domain list would break constantly.
     *
     * A plain `label.contains(token)` would be no safer than `host.contains(token)` at all here,
     * since none of these tokens contain a literal '.' - any occurrence within the host string is
     * necessarily already confined to a single label. So instead this requires the token to sit at
     * the start or end of the label (matching real-world mirror naming like "mega1", "cdn-mega"),
     * which correctly rejects an unrelated ad domain that merely happens to embed the token in the
     * middle of an unrelated word, e.g. "admegaservers.com".
     */
    fun hostContainsLabelToken(host: String, token: String): Boolean {
        if (token.isEmpty()) return false
        return host.split(".").any { label -> label.startsWith(token) || label.endsWith(token) }
    }

    fun hostContainsAnyLabelToken(host: String, tokens: Collection<String>): Boolean {
        for (token in tokens) {
            if (hostContainsLabelToken(host, token)) return true
        }
        return false
    }
}
