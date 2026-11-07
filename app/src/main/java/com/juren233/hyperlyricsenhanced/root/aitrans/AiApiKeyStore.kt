package com.juren233.hyperlyricsenhanced.root.aitrans

import android.content.Context
import android.content.SharedPreferences
import com.juren233.hyperlyricsenhanced.common.RootConstants

/**
 * Credential store isolated from the Xposed-synced app preferences.
 *
 * Kept in a separate app-private preferences file so Android backup rules can
 * exclude it. Keys never need to cross the Apple Music Binder boundary.
 */
internal object AiApiKeyStore {
    const val PREFERENCES_FILE = "hyperlyricsenhanced_private_ai_credentials"
    private const val KEY_POOL = "api_keys"
    private const val KEY_GROQ = "groq_api_key" // earlier single-key preview
    private const val KEY_GROQ_POOL = "groq_api_keys"

    private fun privatePrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    fun read(context: Context, legacyPrefs: SharedPreferences): List<String> {
        val secure = privatePrefs(context)
        if (secure.contains(KEY_POOL)) {
            return AiApiKeyPool.parse(secure.getString(KEY_POOL, null))
        }
        // A saved pool from early test builds is migrated without syncing to NPatch.
        return AiApiKeyPool.parse(
            configured = legacyPrefs.getString(RootConstants.KEY_HOOK_AI_TRANS_API_KEYS, null),
            legacy = legacyPrefs.getString(RootConstants.KEY_HOOK_AI_TRANS_API_KEY, null),
        )
    }

    /** Read the saved pool or migrate an earlier single Groq key, without exporting it. */
    fun readGroqKeys(context: Context): List<String> {
        val secure = privatePrefs(context)
        return if (secure.contains(KEY_GROQ_POOL)) {
            AiApiKeyPool.parse(secure.getString(KEY_GROQ_POOL, null))
        } else {
            AiApiKeyPool.parse(secure.getString(KEY_GROQ, null))
        }
    }

    fun saveGroqKeys(context: Context, rawKeys: String): List<String> {
        val normalized = AiApiKeyPool.parse(rawKeys)
        check(privatePrefs(context).edit()
            .putString(KEY_GROQ_POOL, normalized.joinToString("\n"))
            .remove(KEY_GROQ)
            .commit()) { "Failed to save local Groq credentials" }
        return normalized
    }

    /** Legacy single-key API remains available to earlier callers. */
    fun readGroq(context: Context): String? = readGroqKeys(context).firstOrNull()
    fun saveGroq(context: Context, rawKey: String) { saveGroqKeys(context, rawKey) }

    fun save(context: Context, legacyPrefs: SharedPreferences, rawKeys: String): List<String> {
        val normalized = AiApiKeyPool.parse(rawKeys)
        check(privatePrefs(context).edit()
            .putString(KEY_POOL, normalized.joinToString("\n"))
            .commit()) { "Failed to save local API keys" }
        // Remove the transient, main-preferences version from early preview builds.
        // The former single-key preference stays untouched for legacy Xposed users.
        legacyPrefs.edit().remove(RootConstants.KEY_HOOK_AI_TRANS_API_KEYS).apply()
        return normalized
    }
}
