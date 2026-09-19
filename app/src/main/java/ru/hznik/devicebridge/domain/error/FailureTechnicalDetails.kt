package ru.hznik.devicebridge.domain.error

fun UserFacingFailure.toTechnicalDetailsText(): String = buildString {
    appendLine("Код: ${code.wireValue}")
    appendLine("Состояние: ${severity.name.lowercase()}")
    appendLine(
        "Действия: " + recoveryActions
            .map { it.name.lowercase() }
            .sorted()
            .joinToString(", "),
    )
    context.values.toSortedMap().forEach { (key, value) ->
        appendLine("$key: $value")
    }
}.trimEnd()
