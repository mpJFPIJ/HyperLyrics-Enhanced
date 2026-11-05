package com.juren233.hyperlyricsenhanced.root.aitrans

import android.content.Context
import android.util.Log
import com.worksap.nlp.sudachi.Dictionary
import com.worksap.nlp.sudachi.DictionaryFactory
import com.worksap.nlp.sudachi.Tokenizer
import java.io.File

/**
 * Offline Japanese morphological readings. The bundled V1 dictionary is lazily
 * extracted once in the Enhanced process, never in the patched Apple process.
 */
internal object SudachiReadingEngine {
    private const val TAG = "SudachiReadingEngine"
    private const val DICTIONARY_ASSET = "sudachi_system_core.dic"
    private const val DICTIONARY_FILE = "sudachi_core_v1_20260723.dic"
    private val lock = Any()

    @Volatile private var context: Context? = null
    @Volatile private var dictionary: Dictionary? = null
    @Volatile private var initializationFailed = false

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /** One dictionary pass over the original complete line, never one pass per karaoke cell. */
    fun analyzeLine(text: String): LineReadingResult {
        if (text.isBlank()) return LineReadingResult(null, emptyList(), LineReadingStatus.EMPTY)
        val dic = getDictionary()
            ?: return LineReadingResult(null, emptyList(), LineReadingStatus.MISSING_UNSUPPORTED)
        return runCatching {
            val tokens = dic.create().tokenize(Tokenizer.SplitMode.C, text).map { word ->
                RawReadingToken(
                    sourceStart = word.begin(),
                    sourceEnd = word.end(),
                    readingForm = word.readingForm(),
                    isParticle = word.partOfSpeech().firstOrNull() == "助詞",
                )
            }
            SourceReadingAssembler.assemble(text, tokens)
        }.getOrElse {
            Log.w(TAG, "Full-line reading failed: ${it.javaClass.simpleName}")
            LineReadingResult(null, emptyList(), LineReadingStatus.MISSING_UNSUPPORTED)
        }
    }

    fun reading(text: String): String? {
        val dic = getDictionary() ?: return null
        return runCatching {
            val morphemes = dic.create().tokenize(Tokenizer.SplitMode.C, text)
            val result = StringBuilder()
            var previousEnd = 0
            morphemes.forEach { unit ->
                val surface = unit.surface()
                val reading = when (surface) {
                    "は" -> "ワ"
                    "へ" -> "エ"
                    "を" -> "オ"
                    else -> unit.readingForm().takeIf(String::isNotBlank) ?: surface
                }
                if (result.isNotEmpty() && result.last() != ' ' &&
                    (unit.begin() > previousEnd || surface !in setOf(
                        "の", "は", "が", "を", "へ", "に", "で", "と", "も", "ね", "よ", "か",
                    ))) result.append(' ')
                result.append(reading)
                previousEnd = unit.end()
            }
            result.toString().trim().takeIf(String::isNotBlank)
        }.getOrNull()
    }

    private fun getDictionary(): Dictionary? {
        dictionary?.let { return it }
        if (initializationFailed) return null
        synchronized(lock) {
            dictionary?.let { return it }
            if (initializationFailed) return null
            return try {
                val app = context ?: return null
                val target = File(app.filesDir, DICTIONARY_FILE)
                if (!target.isFile || target.length() < 1_000_000L) {
                    val temp = File(app.filesDir, "$DICTIONARY_FILE.tmp")
                    try {
                        app.assets.open(DICTIONARY_ASSET).use { input ->
                            temp.outputStream().use { output -> input.copyTo(output) }
                        }
                        require(temp.length() >= 1_000_000L) { "Missing Sudachi dictionary" }
                        check(temp.renameTo(target)) { "Unable to install Sudachi dictionary" }
                    } finally {
                        temp.delete()
                    }
                }
                @Suppress("DEPRECATION")
                val loaded = DictionaryFactory().create(
                    """{"systemDict":"${target.absolutePath}"}""",
                )
                dictionary = loaded
                loaded
            } catch (error: Exception) {
                initializationFailed = true
                Log.w(TAG, "Offline reading unavailable: ${error.javaClass.simpleName}")
                null
            }
        }
    }
}
