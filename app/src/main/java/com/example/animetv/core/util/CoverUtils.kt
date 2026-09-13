package com.example.animetv.core.util

object CoverUtils {
    /**
     * Detects if a URL is a website logo, platform logo, or placeholder banner
     * rather than an actual anime/movie cover poster.
     */
    fun isLogoOrInvalidCover(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.lowercase().trim()

        if (lower.contains("platform-logo")) return true
        // Known TMDB platform provider logos from SoloLatino navbar
        if (lower.contains("wwemzkwzjkyjffceib57q3r4bcm")) return true // Netflix
        if (lower.contains("w7hflnm9cwwrmam58udl2l7we7")) return true // Prime Video
        if (lower.contains("hsdroyvthq3cynxtiiy7lns8w1")) return true // Tokyo MX
        if (lower.contains("1edzoyafoyzyz3rklnsiupxx30q")) return true // Disney+
        if (lower.contains("bnghrfi794mnmq34gfvcm9ndxn1")) return true // Apple TV+
        if (lower.contains("jnuo8pznebleq5yaop1f5okmg91")) return true // TV Tokyo
        if (lower.contains("pqutclennuitlavlelgxugwn1elh")) return true // Hulu
        if (lower.contains("ferjnderpevejmqzccjbjdi93rj")) return true // AT-X
        if (lower.contains("gqwi9y0owo9sxgzzd7txoeilyi9")) return true // HBO Max
        if (lower.contains("tuomphy2utuptqqfnkmvhvsb724")) return true // HBO
        if (lower.contains("jq5bx6n7qmdmyqz6sqjo5fz2ir")) return true // BS11
        if (lower.contains("4x4gsmrmsslll0rvnksoqavh43tz")) return true // Fuji TV

        // TMDB small platform logos are pngs under w92 or w185
        if ((lower.contains("/w92/") || lower.contains("/w185/")) && lower.endsWith(".png")) return true

        // General site logos / placeholders
        if (lower.contains("/logo.") || lower.contains("/logo-") || lower.contains("-logo.") || lower.contains("/logos/")) return true
        if (lower.contains("sololatino-logo") || lower.contains("tv_banner") || lower.contains("favicon")) return true
        if (lower.contains("no-poster") || lower.contains("sin-poster") || lower.contains("default-poster")) return true
        if (lower.contains("morencius") && lower.contains("logo")) return true

        return false
    }

    fun isValidCover(url: String?): Boolean = !isLogoOrInvalidCover(url)

    /**
     * Resolves the best available cover between detail and card, ensuring logos are filtered out.
     */
    fun pickBestCover(detailPoster: String?, cardPoster: String?): String {
        return when {
            isValidCover(detailPoster) -> detailPoster!!.trim()
            isValidCover(cardPoster) -> cardPoster!!.trim()
            !detailPoster.isNullOrBlank() && !isLogoOrInvalidCover(detailPoster) -> detailPoster.trim()
            !cardPoster.isNullOrBlank() && !isLogoOrInvalidCover(cardPoster) -> cardPoster.trim()
            else -> ""
        }
    }
}
