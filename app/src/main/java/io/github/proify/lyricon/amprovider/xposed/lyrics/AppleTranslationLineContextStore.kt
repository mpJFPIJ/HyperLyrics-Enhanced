package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap

/** One original lyric occurrence; its translation shares this native line clock. */
internal data class AppleTranslationLineContext(
    val songId: String,
    val sourceText: String,
    val beginMs: Long,
    val endMs: Long,
    val translation: String,
    val revision: Long,
)

/** A renderer can bind the same holder more than once before it is recycled. */
internal class AppleTranslationLineContextStore {
    private val contexts = WeakIdentityMap<Any, AppleTranslationLineContext>()

    fun remember(holder: Any?, context: AppleTranslationLineContext) {
        holder?.let { contexts[it] = context }
    }

    fun forHolder(holder: Any?): AppleTranslationLineContext? =
        holder?.let { contexts[it] }

    fun clear(holder: Any?) {
        holder?.let(contexts::remove)
    }
}
