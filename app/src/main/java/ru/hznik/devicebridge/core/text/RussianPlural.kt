package ru.hznik.devicebridge.core.text

/**
 * The Russian word form for [count]: [one] for 1, 21, 101…, [few] for 2–4, 22–24…,
 * [many] for 0, 5–20, 25–30… and every number ending in 11–14.
 */
fun ruPlural(count: Int, one: String, few: String, many: String): String {
    val lastTwo = count % 100
    val last = count % 10
    return when {
        lastTwo in 11..14 -> many
        last == 1 -> one
        last in 2..4 -> few
        else -> many
    }
}
