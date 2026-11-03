package com.juren233.hyperlyricsenhanced;

oneway interface IAppleMusicTranslationReceiver {
    void onOnlineTranslationResult(in byte[] compressedSong);
    void onOnlineTranslationCleared(String songId);
    void onMissingLyricsSupplementCleared(String songId);
    void onOnlineTranslationSourceSwitchResult(
        long requestId,
        String songId,
        String contentType,
        String requestedSource,
        String actualSource,
        boolean successful
    );
    void onMissingLyricsSupplementResult(in byte[] compressedSong);
    /** Status labels and counters only. Never contains keys, lyrics, or AI response text. */
    void onAiTranslationProgress(String stage, int batch, int totalBatches, int fragments);
}
