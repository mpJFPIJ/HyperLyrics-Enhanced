package com.juren233.hyperlyricsenhanced.utils.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Private-repository gate is evaluated before any diagnostic payload is transmitted. */
internal object DiagnosticGitHubRequestPolicy {
    private val shaPattern = Regex("[0-9a-fA-F]{40}")

    fun isPrivate(status: Int, body: String): Boolean = status == 200 && runCatching {
        val json = Json.parseToJsonElement(body) as? JsonObject
        (json?.get("private") as? JsonPrimitive)?.booleanOrNull == true
    }.getOrDefault(false)

    fun existingSha(status: Int, body: String?): String? {
        if (status != 200) return null
        return runCatching {
            val json = Json.parseToJsonElement(body.orEmpty()) as? JsonObject
            (json?.get("sha") as? JsonPrimitive)?.contentOrNull
                ?.takeIf(shaPattern::matches)
        }.getOrNull()
    }
}
