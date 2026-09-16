package ru.hznik.devicebridge.web

object SessionBearerAuthorization {
    private val tokenPattern = Regex("^[A-Za-z0-9_-]{1,256}$")

    fun parse(values: List<String>): String? {
        if (values.size != 1) return null
        val value = values.single()
        if (!value.startsWith(BEARER_PREFIX)) return null
        val token = value.removePrefix(BEARER_PREFIX)
        return token.takeIf(tokenPattern::matches)
    }

    private const val BEARER_PREFIX = "Bearer "
}
