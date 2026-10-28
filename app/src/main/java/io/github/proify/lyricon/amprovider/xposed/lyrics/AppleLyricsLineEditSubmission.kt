package io.github.proify.lyricon.amprovider.xposed.lyrics

/** A failed editor write must not close the dialog or discard the user's typed text. */
internal object AppleLyricsLineEditSubmission {
    enum class Result(val canDismiss: Boolean) {
        SAVED(true),
        SONG_CHANGED(false),
        SAVE_FAILED(false),
    }

    fun submit(editorSongId: String, currentSongId: String?, save: () -> Boolean): Result {
        if (editorSongId != currentSongId) return Result.SONG_CHANGED
        return if (runCatching(save).getOrDefault(false)) Result.SAVED else Result.SAVE_FAILED
    }
}
