package com.juren233.hyperlyricsenhanced;

import com.juren233.hyperlyricsenhanced.IAppleMusicTranslationReceiver;

/**
 * Isolated translation endpoint for Apple Music-only (e.g. NPatch) installations.
 * The app process owns the API credentials. Only song data crosses this Binder.
 */
oneway interface IAppleMusicAiTranslator {
    void translate(in byte[] compressedSong, IAppleMusicTranslationReceiver receiver);
    void cancel();
}
