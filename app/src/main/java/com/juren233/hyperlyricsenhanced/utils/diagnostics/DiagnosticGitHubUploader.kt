package com.juren233.hyperlyricsenhanced.utils.diagnostics

import android.content.Context
import android.os.Build
import android.util.Base64
import com.juren233.hyperlyricsenhanced.utils.LogManager
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Private-only GitHub Contents upload. The body contains structured diagnostics, not raw logs. */
internal object DiagnosticGitHubUploader {
    private const val MIN_INTERVAL_MS = 15 * 60 * 1000L
    private const val CONTENT_PATH = "diagnostics/latest.txt"
    private val mutex = Mutex()
    private val json = Json { explicitNulls = false; encodeDefaults = false }

    @Serializable
    private data class PutFile(
        val message: String,
        val content: String,
        val sha: String? = null,
    )

    suspend fun upload(context: Context, force: Boolean = false): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val config = DiagnosticGitHubSettings.configuration(app)
            val repository = config.repository
            val credential = DiagnosticGitHubSettings.readToken(app)
            if (!config.enabled || repository == null || credential == null) {
                return@withContext "Not configured"
            }
            val elapsed = System.currentTimeMillis() - DiagnosticGitHubSettings.lastAttempt(app)
            if (!force && elapsed >= 0L && elapsed < MIN_INTERVAL_MS) return@withContext "Paced"

            // Record the attempt first so repeated errors do not cause a GitHub request storm.
            DiagnosticGitHubSettings.recordAttempt(app, "Uploading", succeeded = false)
            val result = try {
                uploadVerifiedPrivate(app, repository, credential)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "Upload failed (${error.javaClass.simpleName})"
            }
            DiagnosticGitHubSettings.recordAttempt(app, result, result.startsWith("Uploaded"))
            result
        }
    }

    private suspend fun uploadVerifiedPrivate(context: Context, repo: String, token: String): String {
        val base = "https://api.github.com/repos/$repo"
        val metadata = get("$base", token)
        if (!DiagnosticGitHubRequestPolicy.isPrivate(metadata.first, metadata.second)) {
            return if (metadata.first == 200) "Repository is not private"
            else "Repository check failed (HTTP ${metadata.first})"
        }

        val existing = get("$base/contents/$CONTENT_PATH", token)
        if (existing.first !in setOf(200, 404)) {
            return "File check failed (HTTP ${existing.first})"
        }
        val sha = DiagnosticGitHubRequestPolicy.existingSha(existing.first, existing.second)
        if (existing.first == 200 && sha == null) return "Invalid file metadata"

        val appEntries = LogManager.readAppLogs().asSequence()
            .mapNotNull { DiagnosticPrivacyPolicy.event(it.timestamp, "app", it.message) }
            .take(160).toList()
        val moduleEntries = runCatching { LogManager.readModuleLogs(context) }
            .getOrDefault(emptyList()).asSequence()
            .mapNotNull { DiagnosticPrivacyPolicy.event(it.timestamp, "xposed", it.message) }
            .take(120).toList()
        val structured = DiagnosticPrivacyPolicy.snapshot(appEntries + moduleEntries)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((repo + "\n" + structured).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (DiagnosticGitHubSettings.lastDigest(context) == digest) return "No new diagnostics"
        val report = "Captured: ${Instant.now()}\nAndroid API: ${Build.VERSION.SDK_INT}\n" + structured
        val encoded = Base64.encodeToString(report.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val body = json.encodeToString(PutFile(
            message = "Update private HyperLyrics diagnostics",
            content = encoded,
            sha = sha,
        ))
        if (!DiagnosticGitHubSettings.configuration(context).enabled) return "Upload disabled"
        val code = put("$base/contents/$CONTENT_PATH", token, body)
        return if (code == 200 || code == 201) {
            DiagnosticGitHubSettings.setLastDigest(context, digest)
            "Uploaded (HTTP $code)"
        } else "Upload failed (HTTP $code)"
    }

    private fun get(url: String, token: String): Pair<Int, String> {
        val conn = open(url, token)
        return try {
            val code = conn.responseCode
            val body = if (code == 200) conn.inputStream.bufferedReader(Charsets.UTF_8)
                .use { it.readText().take(512 * 1024) } else ""
            code to body
        } finally { conn.disconnect() }
    }

    private fun put(url: String, token: String, body: String): Int {
        val conn = open(url, token)
        return try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } finally { conn.disconnect() }
    }

    private fun open(url: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "HyperLyrics-Enhanced-Private-Diagnostics")
        }
}
