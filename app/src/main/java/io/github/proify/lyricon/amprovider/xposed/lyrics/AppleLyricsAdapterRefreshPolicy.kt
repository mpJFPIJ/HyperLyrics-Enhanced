package io.github.proify.lyricon.amprovider.xposed

import java.lang.reflect.Method

/** Rebinder for Apple's 6.5.3 obfuscated RecyclerView.Adapter methods. */
internal object AppleLyricsAdapterRefreshPolicy {
    fun notifyRange(adapter: Any, range: IntRange, profiledItemChanged: Method?): Int {
        val count = range.last - range.first + 1
        if (count <= 0) return 0

        if (runCatching {
                AppleReflection.call(adapter, "notifyItemRangeChanged", range.first, count)
            }.isSuccess
        ) return count
        if (runCatching {
                range.forEach { position ->
                    AppleReflection.call(adapter, "notifyItemChanged", position)
                }
            }.isSuccess
        ) return count

        // On Apple Music 6.5.3 RecyclerView.Adapter is R8-obfuscated:
        // its single-item notification is androidx.recyclerview...$f#h(int).
        // The versioned hook profile identifies that exact inherited method.
        val method = profiledItemChanged?.takeIf { candidate ->
            candidate.declaringClass.isInstance(adapter) &&
                candidate.parameterCount == 1 &&
                candidate.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: throw NoSuchMethodException(
            "${adapter.javaClass.name}#notifyItemChanged/profiled/${range.first}..${range.last}",
        )
        method.isAccessible = true
        range.forEach { position -> method.invoke(adapter, position) }
        return count
    }
}
