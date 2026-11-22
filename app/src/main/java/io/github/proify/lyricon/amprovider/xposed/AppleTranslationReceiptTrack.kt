package io.github.proify.lyricon.amprovider.xposed

/** A receipt may finish decoding after playback returns to the same song ID. */
internal class AppleTranslationReceiptTrack {
    private class Selection(val id: String?)

    @Volatile private var selected = Selection(null)

    @Synchronized
    fun select(id: String?) {
        if (selected.id != id) selected = Selection(id)
    }

    @Synchronized
    fun invalidate(id: String?) {
        if (id.isNullOrBlank() || id == selected.id) selected = Selection(selected.id)
    }

    fun ticket(): (String?) -> Boolean {
        val captured = selected
        return { id -> !id.isNullOrBlank() && id == captured.id && selected === captured }
    }
}
