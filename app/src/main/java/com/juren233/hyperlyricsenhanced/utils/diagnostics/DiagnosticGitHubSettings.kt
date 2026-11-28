package com.juren233.hyperlyricsenhanced.utils.diagnostics

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Never store the upload token in Xposed-synchronized preferences or Android backup. */
internal object DiagnosticGitHubSettings {
    const val PREFS_FILE = "hyperlyricsenhanced_private_diagnostics"
    private const val KEY_ALIAS = "hyperlyricsenhanced_diagnostic_github_token_v1"
    private const val REPOSITORY = "repository"
    private const val TOKEN = "encrypted_token"
    private const val ENABLED = "enabled"
    private const val LAST_SUCCESS = "last_success_ms"
    private const val LAST_ATTEMPT = "last_attempt_ms"
    private const val LAST_STATUS = "last_status"
    private const val LAST_DIGEST = "last_uploaded_digest"

    data class Configuration(val repository: String?, val enabled: Boolean, val hasToken: Boolean)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        PREFS_FILE, Context.MODE_PRIVATE,
    )

    fun configuration(context: Context): Configuration {
        val p = prefs(context)
        val repo = DiagnosticPrivacyPolicy.repository(p.getString(REPOSITORY, "").orEmpty())
        val hasToken = readToken(context) != null
        return Configuration(repo, p.getBoolean(ENABLED, false), hasToken)
    }

    fun save(context: Context, repository: String, newToken: String, enabled: Boolean) {
        val normalized = requireNotNull(DiagnosticPrivacyPolicy.repository(repository)) {
            "Enter a repository in owner/repo format"
        }
        val entered = newToken.trim()
        require(entered.isEmpty() || entered.length in 15..500) { "Invalid token length" }
        val encrypted = if (entered.isEmpty()) null else encrypt(entered)
        val haveToken = encrypted != null || readToken(context) != null
        require(!enabled || haveToken) { "Set a fine-grained GitHub token first" }
        val editor = prefs(context).edit().putString(REPOSITORY, normalized)
            .putBoolean(ENABLED, enabled)
        if (encrypted != null) editor.putString(TOKEN, encrypted)
        check(editor.commit()) { "Unable to save diagnostic settings" }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            val configuration = configuration(context)
            require(configuration.repository != null && configuration.hasToken) {
                "Configure a private repository and token first"
            }
        }
        check(prefs(context).edit().putBoolean(ENABLED, enabled).commit())
    }

    fun disconnect(context: Context) {
        check(prefs(context).edit().remove(TOKEN).putBoolean(ENABLED, false).commit())
    }

    fun readToken(context: Context): String? = runCatching {
        val encoded = prefs(context).getString(TOKEN, null) ?: return@runCatching null
        decrypt(encoded).takeIf(String::isNotBlank)
    }.getOrNull()

    fun lastAttempt(context: Context): Long = prefs(context).getLong(LAST_ATTEMPT, 0L)
    fun lastSuccess(context: Context): Long = prefs(context).getLong(LAST_SUCCESS, 0L)
    fun lastStatus(context: Context): String = prefs(context).getString(LAST_STATUS, "Not uploaded yet")
        ?: "Not uploaded yet"
    fun lastDigest(context: Context): String? = prefs(context).getString(LAST_DIGEST, null)
    fun setLastDigest(context: Context, digest: String) {
        prefs(context).edit().putString(LAST_DIGEST, digest).apply()
    }

    fun recordAttempt(context: Context, status: String, succeeded: Boolean) {
        val now = System.currentTimeMillis()
        prefs(context).edit().putLong(LAST_ATTEMPT, now)
            .putString(LAST_STATUS, status.take(90))
            .apply {
                if (succeeded) putLong(LAST_SUCCESS, now)
            }.apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(secret: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val data = cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        require(data.size > 12) { "Corrupt credential" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
    }
}
