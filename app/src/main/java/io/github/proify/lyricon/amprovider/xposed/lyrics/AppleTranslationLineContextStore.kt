package io.github.proify.lyricon.amprovider.xposed

import io.github.proify.lyricon.amprovider.xposed.internal.WeakIdentityMap
import java.lang.ref.WeakReference

/** One original lyric occurrence; its translation shares this native line clock. */
internal data class AppleTranslationLineContext(
    val songId: String,
    val sourceText: String,
    val beginMs: Long,
    val endMs: Long,
    val translation: String,
    val revision: Long,
    /** Displayed native word-getter concatenation (if verified). This may
     * differ from whole-line Traditional→Simplified phrase conversion. */
    val displayedSourceText: String? = null,
)

/** A renderer can bind the same holder more than once before it is recycled. */
internal class AppleTranslationLineContextStore {
    private data class Entry(
        val context: AppleTranslationLineContext,
        val adapterOwner: WeakReference<Any>?,
        val adapterPosition: Int?,
    )
    private val contexts = WeakIdentityMap<Any, Entry>()

    fun remember(
        holder: Any?, context: AppleTranslationLineContext,
        adapterOwner: Any? = null, adapterPosition: Int? = null,
    ) {
        holder?.let {
            contexts[it] = Entry(
                context, adapterOwner?.let { owner -> WeakReference(owner) },
                adapterPosition?.takeIf { position -> position >= 0 },
            )
        }
    }

    fun forHolder(
        holder: Any?, adapterOwner: Any? = null, adapterPosition: Int? = null,
    ): AppleTranslationLineContext? {
        val entry = holder?.let { contexts[it] } ?: return null
        if (adapterOwner != null && entry.adapterOwner != null &&
            entry.adapterOwner.get() !== adapterOwner
        ) return null
        if (adapterPosition != null && entry.adapterPosition != null &&
            entry.adapterPosition != adapterPosition
        ) return null
        return entry.context
    }

    fun clear(holder: Any?) {
        holder?.let(contexts::remove)
    }
}
