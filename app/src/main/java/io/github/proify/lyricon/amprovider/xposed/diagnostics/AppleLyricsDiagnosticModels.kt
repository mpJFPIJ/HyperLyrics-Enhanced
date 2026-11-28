/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

internal data class AppleDebugWordTiming(
    val wordId: Int,
    val begin: Int,
    val end: Int,
    val text: String,
)

internal data class AppleLyricsBindingDiagnosticContext(
    val songId: String?,
    val adapterClass: String,
    val adapterIdentity: Int,
    val methodName: String,
    val holder: Any?,
    val position: Int?,
    val translationEnabled: Boolean?,
    val pronunciationEnabled: Boolean?,
    val nestedForSameHolder: Boolean = false,
    /** Real adapter object, not its potentially colliding identityHashCode.
     * Held only within the short-lived native bind context. */
    val adapterInstance: Any? = null,
    /** Translation/source revision captured on entry to this native holder bind. */
    val presentationRevision: Long = -1L,
)

/** Two- and three-parameter native bind methods often call each other. */
internal object AppleLyricsBindingPassPolicy {
    fun isNestedForSameHolder(
        parentHolder: Any?,
        parentAdapterIdentity: Int?,
        holder: Any?,
        adapterIdentity: Int,
    ): Boolean = holder != null && parentHolder === holder &&
        parentAdapterIdentity == adapterIdentity
}
