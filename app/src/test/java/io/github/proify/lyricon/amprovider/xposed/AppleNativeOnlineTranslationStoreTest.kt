package io.github.proify.lyricon.amprovider.xposed

import com.juren233.hyperlyricsenhanced.lyric.model.RichLyricLine
import com.juren233.hyperlyricsenhanced.lyric.model.LyricWordPronunciation
import com.juren233.hyperlyricsenhanced.lyric.model.LyricPronunciationSpan
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.lyric.model.lyricMetadataOf
import com.juren233.hyperlyricsenhanced.common.lyric.LyricMetadataKeys
import com.juren233.hyperlyricsenhanced.service.AppleMusicAiTranslationPolicy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleNativeOnlineTranslationStoreTest {
    @Test fun `same sound provider can reuse exact AI source tokens without changing provider text`() {
        val store = AppleNativeOnlineTranslationStore()
        val spans = listOf(
            LyricPronunciationSpan(0, 1, "駄", "다"),
            LyricPronunciationSpan(1, 2, "目", "메"),
        )
        store.receive(song("A", RichLyricLine(
            begin = 100, end = 200, text = "駄目", roma = "다메",
            pronunciationSpans = spans,
        )), preserveExistingPronunciation = true)
        store.receive(song("A", RichLyricLine(
            begin = 100, end = 200, text = "駄目", roma = "다메", translation = "안 돼",
        )))
        assertEquals(spans, store.soundMatchedPronunciationSpans(
            "A", 100, 200, "駄目", "다메",
        ))
        assertEquals("다메", store.pronunciation("A", 100, 200, "駄目"))
        assertNull(store.soundMatchedPronunciationSpans("A", 100, 200, "駄目", "다미"))
        assertNull(store.soundMatchedPronunciationSpans("A", 100, 200, "余計", "다메"))
        assertNull(store.soundMatchedPronunciationSpans("B", 100, 200, "駄目", "다메"))
        assertNull(store.soundMatchedPronunciationSpans("A", 100, 200, "駄目", "다2메"))
    }

    @Test fun `source spans containing unspoken digits cannot masquerade as verified sounds`() {
        val store = AppleNativeOnlineTranslationStore()
        store.receive(song("A", RichLyricLine(
            begin = 100, end = 200, text = "駄目", roma = "다2메",
            pronunciationSpans = listOf(LyricPronunciationSpan(0, 2, "駄目", "다2메")),
        )), preserveExistingPronunciation = true)
        assertNull(store.soundMatchedPronunciationSpans("A", 100, 200, "駄目", "다메"))
    }

    @Test fun `partial reading anchors cannot impersonate a complete row sound mapping`() {
        val store = AppleNativeOnlineTranslationStore()
        store.receive(song("A", RichLyricLine(
            begin = 100, end = 200, text = "駄目", roma = "다메",
            pronunciationSpans = listOf(LyricPronunciationSpan(0, 1, "駄", "다")),
        )), preserveExistingPronunciation = true)
        assertNull(store.soundMatchedPronunciationSpans("A", 100, 200, "駄目", "다메"))
    }

    @Test fun `provider spaces inside one verified Japanese sound do not become false boundaries`() {
        val store = AppleNativeOnlineTranslationStore()
        store.receive(song("A", RichLyricLine(
            begin = 100, end = 200, text = "駄目", roma = "다메",
            pronunciationSpans = listOf(LyricPronunciationSpan(0, 2, "駄目", "다메")),
        )), preserveExistingPronunciation = true)
        assertNull(store.soundMatchedPronunciationSpans("A", 100, 200, "駄目", "다 메"))
    }

    @Test fun `Korean AI and Chinese provider meanings survive transport with independent revision`() {
        val provider = RichLyricLine(begin = 100, end = 300, text = "ありがとう", translation = "谢谢")
        val store = AppleNativeOnlineTranslationStore()
        store.receive(song("A", provider))
        fun deliver(korean: String): AppleNativeOnlineTranslationStore.Receipt {
            val line = AppleMusicAiTranslationPolicy.transportLine(
                provider, provider.copy(translation = korean), forceOverride = false,
            )
            val payload = Json.encodeToString(song("A", line))
            return store.receive(Json.decodeFromString<Song>(payload), preserveExistingPronunciation = true)
        }
        val first = deliver("고마워")
        assertTrue(first.displayContentChanged)
        assertTrue(store.hasAiTranslation("A"))
        assertEquals("谢谢", store.translation("A", 100, 300, "ありがとう"))
        assertEquals("고마워", store.aiTranslation("A", 100, 300, "ありがとう"))
        assertFalse(deliver("고마워").updated)
        val revised = deliver("고맙습니다")
        assertTrue(revised.displayContentChanged)
        assertTrue(first.update.revision != revised.update.revision)
        store.receive(song("A", provider))
        assertEquals("谢谢", store.translation("A", 100, 300, "ありがとう"))
        assertEquals("고맙습니다", store.aiTranslation("A", 100, 300, "ありがとう"))
    }

    @Test fun `inherited provider meanings remain selectable without becoming AI translations`() {
        val line = RichLyricLine(begin = 100, end = 300, text = "ありがとう", translation = "고마워", roma = "아리가토",
            metadata = lyricMetadataOf(LyricMetadataKeys.STANDALONE_INHERITED_TRANSLATION to "true"))
        val store = AppleNativeOnlineTranslationStore()
        store.receive(song("A", line), preserveExistingPronunciation = true)
        assertTrue(store.hasTranslation("A"))
        assertEquals("고마워", store.translation("A", 100, 300, "ありがとう"))
        assertFalse(store.hasAiTranslation("A"))
        assertNull(store.aiTranslation("A", 100, 300, "ありがとう"))
        assertEquals("아리가토", store.aiHangulPronunciation("A", 100, 300, "ありがとう"))
    }

    @Test fun `first AI receipt refreshes AI first rows even when provider values match`() {
        val store = AppleNativeOnlineTranslationStore()
        val payload = song("A", RichLyricLine(
            begin = 100, end = 200, text = "ありがとう", roma = "아리가토", translation = "고마워",
        ))
        val provider = store.receive(payload)
        assertNull(store.aiHangulPronunciation("A", 100, 200, "ありがとう"))
        val firstAi = store.receive(payload, preserveExistingPronunciation = true)
        assertTrue(firstAi.updated)
        assertTrue(firstAi.displayContentChanged)
        assertTrue(firstAi.update.revision != provider.update.revision)
        assertEquals("아리가토", store.aiHangulPronunciation("A", 100, 200, "ありがとう"))
        val replay = store.receive(payload, preserveExistingPronunciation = true)
        assertFalse(replay.updated)
        assertFalse(replay.displayContentChanged)
    }

    @Test fun `replacement lyric at the same timestamp does not inherit another lines reading`() {
        val store = AppleNativeOnlineTranslationStore()
        val original = song("A", RichLyricLine(begin = 100, end = 200, text = "ありがとう", roma = "아리가토"))
        store.receive(original, preserveExistingPronunciation = true)
        val revised = song("A", RichLyricLine(begin = 100, end = 200, text = "さよなら", translation = "잘 가"))
        store.receive(revised, preserveExistingPronunciation = true)
        assertNull(store.aiHangulPronunciation("A", 100, 200, "さよなら"))
        assertNull(store.pronunciation("A", 100, 200, "さよなら"))
        assertEquals("잘 가", store.translation("A", 100, 200, "さよなら"))
    }

    @Test fun `slow direct decode cannot replace a newer accepted receipt for the same song`() {
        val store = AppleNativeOnlineTranslationStore()
        val line = RichLyricLine(begin = 100, end = 200, text = "ありがとう", translation = "first")
        val first = store.nextDirectReceipt()
        val latest = store.nextDirectReceipt()
        store.receive(song("A", line.copy(translation = "final")), directReceipt = latest)
        val ignored = store.receive(song("A", line), directReceipt = first)
        assertFalse(ignored.updated)
        assertFalse(ignored.accepted)
        assertEquals("final", store.translation("A", 100, 200, "ありがとう"))
        val valid = store.nextDirectReceipt()
        store.receive(Song(id = "A"), directReceipt = store.nextDirectReceipt())
        assertTrue(store.receive(song("A", line), directReceipt = valid).updated)
    }

    @Test fun `identical new direct receipt is accepted even when nothing needs repainting`() {
        val store = AppleNativeOnlineTranslationStore()
        val payload = song("A", RichLyricLine(
            begin = 100, end = 200, text = "ありがとう", translation = "고마워",
        ))
        assertTrue(store.receive(payload, directReceipt = store.nextDirectReceipt()).accepted)
        val sameContent = store.receive(payload, directReceipt = store.nextDirectReceipt())
        assertTrue(sameContent.accepted)
        assertFalse(sameContent.updated)
    }

    @Test fun `clearing current song invalidates pending decode and allows a new receipt`() {
        val track = AppleTranslationReceiptTrack()
        track.select("A")
        val delayed = track.ticket()
        track.invalidate("B")
        assertTrue(delayed("A"))
        track.invalidate("A")
        assertFalse(delayed("A"))
        val fresh = track.ticket()
        assertTrue(fresh("A"))
        track.invalidate(null)
        assertFalse(fresh("A"))
    }

    @Test fun `queued receipt cannot evict a newer song or revive across a repeat visit`() {
        val store = AppleNativeOnlineTranslationStore()
        val track = AppleTranslationReceiptTrack()
        val line = RichLyricLine(begin = 100, end = 200, text = "ありがとう", translation = "고마워")
        track.select("A")
        val delayed = track.ticket()
        track.select("A") // Same-song republications keep a valid ticket.
        assertTrue(delayed("A"))
        track.select("B")
        store.receive(song("B", line), preserveExistingPronunciation = true)
        if (delayed("A")) store.receive(song("A", line), preserveExistingPronunciation = true)
        assertTrue(store.hasAiTranslation("B"))
        track.select("A")
        assertFalse(delayed("A"))
        val current = track.ticket()
        assertTrue(current("A"))
        assertFalse(current("B"))
        track.select(null)
        assertFalse(current("A"))
    }

    @Test fun `Japanese phonetics survive small native timing and punctuation differences`() {
        val store = AppleNativeOnlineTranslationStore()
        store.receive(Song(id = "100", lyrics = listOf(
            RichLyricLine(
                begin = 1_000, end = 2_000,
                text = "「君の名は！」", roma = "키미노 나와",
            ),
        )), preserveExistingPronunciation = true)
        assertEquals("키미노 나와",
            store.aiHangulPronunciation("100", 1_240, 2_110, "君 の 名 は"))
        assertNull(store.aiHangulPronunciation("200", 1_240, 2_110, "君 の 名 は"))
        assertNull(store.aiHangulPronunciation("100", 5_000, 6_000, "君 の 名 は"))
    }

    @Test fun `ambiguous repeated lyrics never inherit an adjacent refrain's reading`() {
        val store = AppleNativeOnlineTranslationStore()
        store.receive(Song(id = "100", lyrics = listOf(
            RichLyricLine(begin = 1_000, end = 1_400, text = "は", roma = "와"),
            RichLyricLine(begin = 1_600, end = 2_000, text = "は", roma = "하"),
        )), preserveExistingPronunciation = true)
        assertNull(store.aiHangulPronunciation("100", 1_300, 1_700, "は"))
    }

    @Test fun `source spans survive transport for alignment to actual Apple native word cells`() {
        val store = AppleNativeOnlineTranslationStore()
        val spans = listOf(LyricPronunciationSpan(0, 2, "駄目", "다메"))
        store.receive(Song(id = "100", lyrics = listOf(RichLyricLine(
            begin = 1_000, end = 2_000, text = "駄目", roma = "다메",
            pronunciationSpans = spans,
        ))), preserveExistingPronunciation = true)

        assertEquals(spans, store.aiHangulPronunciationSpans("100", 1_000, 2_000, "駄目"))
        assertNull(store.aiHangulPronunciationSpans("100", 2_000, 3_000, "駄目"))
    }

    @Test fun `translation only AI refresh keeps previously verified reading spans`() {
        val store = AppleNativeOnlineTranslationStore()
        val spans = listOf(LyricPronunciationSpan(0, 2, "駄目", "다메"))
        val first = Song(id = "100", lyrics = listOf(RichLyricLine(
            begin = 1_000, end = 2_000, text = "駄目", roma = "다메",
            pronunciationSpans = spans, pronunciationStatus = "ALIGNED",
            translation = "안 돼",
        )))
        store.receive(first, preserveExistingPronunciation = true)
        store.receive(Song(id = "100", lyrics = listOf(RichLyricLine(
            begin = 1_000, end = 2_000, text = "駄目", roma = null,
            translation = "그러면 안 돼",
        ))), preserveExistingPronunciation = true)

        assertEquals("다메", store.aiHangulPronunciation("100", 1_000, 2_000, "駄目"))
        assertEquals(spans, store.aiHangulPronunciationSpans("100", 1_000, 2_000, "駄目"))
        assertEquals("ALIGNED", store.aiHangulPronunciationStatus("100", 1_000, 2_000, "駄目"))
        assertEquals("그러면 안 돼", store.aiTranslation("100", 1_000, 2_000, "駄目"))
    }
    @Test fun `unsupported pronunciation status survives without manufactured sound`() {
        val store = AppleNativeOnlineTranslationStore()
        store.receive(Song(id = "100", lyrics = listOf(RichLyricLine(
            begin = 3_000, end = 4_000, text = "未知語", roma = null,
            pronunciationStatus = "MISSING_UNSUPPORTED",
        ))), preserveExistingPronunciation = true)
        assertEquals("MISSING_UNSUPPORTED", store.aiHangulPronunciationStatus("100", 3_000, 4_000, "未知語"))
        assertNull(store.aiHangulPronunciation("100", 3_000, 4_000, "未知語"))
        // A reused timing key must never attach an unrelated lyric's reading.
        assertNull(store.aiHangulPronunciationStatus("100", 3_000, 4_000, "別の歌詞"))
        assertNull(store.aiHangulPronunciationStatus("100", 4_000, 5_000, "未知語"))
    }

    @Test fun identicalTimestampWithUnrelatedSourceDoesNotStealKoreanMeaning() {
        val store = AppleNativeOnlineTranslationStore()
        store.receive(Song(id = "iris", lyrics = listOf(
            RichLyricLine(begin = 1_000, end = 2_000,
                text = "駄目駄目", translation = "안 돼 안 돼"),
        )))
        assertNull(store.translation("iris", 1_000, 2_000, "モラリティ"))
        assertEquals("안 돼 안 돼", store.translation("iris", 1_000, 2_000, "駄目 駄目"))
    }

    @Test fun `line wide status remains addressable without exposing stale foreign song`() {
        val store = AppleNativeOnlineTranslationStore()
        store.receive(Song(id = "100", lyrics = listOf(RichLyricLine(
            begin = 1_000, end = 2_000, text = "駄目", roma = "다메",
            pronunciationStatus = "LINE_WIDE",
        ))), preserveExistingPronunciation = true)
        assertEquals("LINE_WIDE", store.aiHangulPronunciationStatus("100", 1_000, 2_000, "駄目"))
        assertNull(store.aiHangulPronunciationStatus("200", 1_000, 2_000, "駄目"))
        store.clear("100")
        assertNull(store.aiHangulPronunciationStatus("100", 1_000, 2_000, "駄目"))
    }

    @Test
    fun `retains verified word readings only in matching standalone AI overlay`() {
        val store = AppleNativeOnlineTranslationStore()
        val words = listOf(
            LyricWordPronunciation("駄目", "다메"),
            LyricWordPronunciation("脳", "노"),
        )
        store.receive(
            Song(id = "100", lyrics = listOf(RichLyricLine(
                begin = 1_000, end = 2_000, text = "駄目脳", roma = "다메 노",
                pronunciationWords = words,
            ))),
            preserveExistingPronunciation = true,
        )
        assertEquals(words, store.aiHangulPronunciationWords("100", 1_000, 2_000, "駄目脳"))
        assertNull(store.aiHangulPronunciationWords("100", 2_000, 3_000, "駄目脳"))
        assertNull(store.aiHangulPronunciationWords("200", 1_000, 2_000, "駄目脳"))
    }

    @Test
    fun `exposes independent online content sources only for available content`() {
        val store = AppleNativeOnlineTranslationStore()
        store.update(
            Song(
                id = "100",
                metadata = lyricMetadataOf(
                    LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE to "QM",
                    LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE to "NE",
                ),
                lyrics = listOf(
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "君の名は",
                        translation = "你的名字",
                        roma = "Kimi no na wa",
                    )
                ),
            )
        )

        assertEquals("QM", store.translationSource("100"))
        assertEquals("NE", store.pronunciationSource("100"))
        assertNull(store.translationSource("200"))
    }
    @Test
    fun `returns exact translation for current song`() {
        val store = AppleNativeOnlineTranslationStore()
        assertTrue(
            store.update(
                song(
                    id = "100",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "Hello world",
                        translation = "你好，世界",
                    ),
                )
            )
        )

        assertTrue(store.hasTranslation("100"))
        assertEquals(
            "你好，世界",
            store.translation("100", 1_000, 2_000, "  Hello   world "),
        )
        assertNull(store.translation("200", 1_000, 2_000, "Hello world"))
    }

    @Test
    fun `does not advance revision for identical online content`() {
        val store = AppleNativeOnlineTranslationStore()
        val song = song(
            id = "100",
            RichLyricLine(
                begin = 1_000,
                end = 2_000,
                text = "Hello world",
                translation = "你好，世界",
            ),
        )

        assertTrue(store.update(song))
        val revision = store.revision()

        assertFalse(store.update(song.copy()))
        assertEquals(revision, store.revision())
        assertTrue(store.isCurrentRevision("100", revision))
        assertFalse(store.isCurrentRevision("200", revision))
    }

    @Test
    fun `advances revision when online content changes or clears`() {
        val store = AppleNativeOnlineTranslationStore()
        assertTrue(
            store.update(
                song(
                    id = "100",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "Hello world",
                        translation = "你好，世界",
                    ),
                )
            )
        )
        val firstRevision = store.revision()

        assertTrue(
            store.update(
                song(
                    id = "100",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "Hello world",
                        translation = "你好世界",
                    ),
                )
            )
        )
        val secondRevision = store.revision()
        assertTrue(secondRevision > firstRevision)
        assertTrue(store.isCurrentRevision("100", secondRevision))
        assertFalse(store.isCurrentRevision("100", firstRevision))

        assertTrue(store.clear("100"))
        assertTrue(store.revision() > secondRevision)
        assertFalse(store.isCurrentRevision("100", secondRevision))
    }

    @Test
    fun `uses timing fallback only when both time and lyric identity agree`() {
        val store = AppleNativeOnlineTranslationStore()
        store.update(
            song(
                id = "100",
                RichLyricLine(
                    begin = 1_000,
                    end = 2_000,
                    text = "Original",
                    translation = "唯一翻译",
                ),
            )
        )

        assertNull(store.translation("100", 1_000, 2_000, "Different wrapper text"))

        store.update(
            song(
                id = "100",
                RichLyricLine(
                    begin = 1_000,
                    end = 2_000,
                    text = "First",
                    translation = "第一句",
                ),
                RichLyricLine(
                    begin = 1_000,
                    end = 2_000,
                    text = "Second",
                    translation = "第二句",
                ),
            )
        )

        assertNull(store.translation("100", 1_000, 2_000, "Unknown"))
        assertEquals("第二句", store.translation("100", 1_000, 2_000, "Second"))
    }

    @Test
    fun `does not replace overlay when payload has no translation`() {
        val store = AppleNativeOnlineTranslationStore()
        store.update(
            song(
                id = "100",
                RichLyricLine(
                    begin = 1_000,
                    end = 2_000,
                    text = "Original",
                    translation = "翻译",
                ),
            )
        )

        assertFalse(
            store.update(
                song(
                    id = "200",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "No translation",
                    ),
                )
            )
        )
        assertTrue(store.hasTranslation("100"))
        assertFalse(store.clear("200"))
        assertTrue(store.clear("100"))
        assertFalse(store.hasTranslation("100"))
    }

    @Test
    fun `does not create overlay from missing translation placeholders`() {
        val store = AppleNativeOnlineTranslationStore()

        listOf(" // ", "// //", "///", "//\n //").forEachIndexed { index, placeholder ->
            assertFalse(
                store.update(
                    song(
                        id = "100-$index",
                        RichLyricLine(
                            begin = 1_000,
                            end = 2_000,
                            text = "Nah",
                            translation = placeholder,
                        ),
                    )
                )
            )
        }
        assertFalse(store.hasTranslation("100-0"))
        assertNull(store.translation("100-0", 1_000, 2_000, "Nah"))
    }

    @Test
    fun `keeps real translation containing slash characters`() {
        val store = AppleNativeOnlineTranslationStore()

        assertTrue(
            store.update(
                song(
                    id = "100",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "Nah",
                        translation = "AC/DC // Live",
                    ),
                )
            )
        )
        assertEquals("AC/DC // Live", store.translation("100", 1_000, 2_000, "Nah"))
    }

    @Test
    fun `keeps valid translations while ignoring placeholder lines`() {
        val store = AppleNativeOnlineTranslationStore()

        assertTrue(
            store.update(
                song(
                    id = "100",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "Hello",
                        translation = "你好",
                    ),
                    RichLyricLine(
                        begin = 2_000,
                        end = 3_000,
                        text = "Nah",
                        translation = "//",
                    ),
                )
            )
        )

        assertTrue(store.hasTranslation("100"))
        assertEquals("你好", store.translation("100", 1_000, 2_000, "Hello"))
        assertNull(store.translation("100", 2_000, 3_000, "Nah"))
    }

    @Test
    fun `tracks pronunciation independently from translation`() {
        val store = AppleNativeOnlineTranslationStore()

        assertTrue(
            store.update(
                song(
                    id = "100",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "君の名は",
                        roma = "Kimi no na wa",
                    ),
                )
            )
        )

        assertFalse(store.hasTranslation("100"))
        assertTrue(store.hasPronunciation("100"))
        assertNull(store.translation("100", 1_000, 2_000, "君の名は"))
        assertEquals(
            "Kimi no na wa",
            store.pronunciation("100", 1_000, 2_000, "君の名は"),
        )
    }

    @Test
    fun `rejects non Roman and copied pronunciation payloads`() {
        val store = AppleNativeOnlineTranslationStore()

        assertFalse(
            store.update(
                song(
                    id = "100",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "Getting washed",
                        roma = "ゲッティング ウォッシュト",
                    ),
                    RichLyricLine(
                        begin = 2_000,
                        end = 3_000,
                        text = "Home",
                        roma = "HOME",
                    ),
                )
            )
        )
        assertFalse(store.hasPronunciation("100"))
        assertNull(store.pronunciation("100", 1_000, 2_000, "Getting washed"))
        assertNull(store.pronunciation("100", 2_000, 3_000, "Home"))
    }

    @Test
    fun `rejects Latin pronunciation assigned to an English lyric line`() {
        val store = AppleNativeOnlineTranslationStore()

        assertFalse(
            store.update(
                song(
                    id = "100",
                    RichLyricLine(
                        begin = 1_000,
                        end = 2_000,
                        text = "Where did you go?",
                        roma = "yi zei yei yv guong zong",
                    ),
                )
            )
        )
        assertFalse(store.hasPronunciation("100"))
        assertNull(
            store.pronunciation(
                "100",
                1_000,
                2_000,
                "Where did you go?",
            )
        )
    }

    @Test
    fun `keeps Apple line lookup isolated for mixed online content`() {
        val store = AppleNativeOnlineTranslationStore()
        store.update(
            song(
                id = "100",
                RichLyricLine(
                    begin = 1_000,
                    end = 2_000,
                    text = "First",
                    translation = "第一句",
                ),
                RichLyricLine(
                    begin = 2_000,
                    end = 3_000,
                    text = "君の名は",
                    roma = "Kimi no na wa",
                ),
            )
        )

        assertTrue(store.hasTranslation("100"))
        assertTrue(store.hasPronunciation("100"))
        assertNull(store.pronunciation("100", 1_000, 2_000, "First"))
        assertNull(store.translation("100", 2_000, 3_000, "君の名は"))
    }

    @Test
    fun `reports display content unchanged when only translation source changes`() {
        val store = AppleNativeOnlineTranslationStore()
        val first = song(
            id = "100",
            RichLyricLine(
                begin = 1_000,
                end = 2_000,
                text = "Hello world",
                translation = "你好，世界",
            ),
        ).copy(
            metadata = lyricMetadataOf(
                LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE to "KUGOU",
            )
        )

        assertReceipt(store, first, updated = true, displayChanged = true, revision = 1)

        val sourceOnlyChange = first.copy(
            metadata = lyricMetadataOf(
                LyricMetadataKeys.ONLINE_TRANSLATION_SOURCE to "NE",
            )
        )
        assertReceipt(store, sourceOnlyChange, updated = true, displayChanged = false, revision = 1)
        assertEquals("NE", store.translationSource("100"))
        assertReceipt(store, sourceOnlyChange.copy(), updated = false, displayChanged = false, revision = 1)
    }

    @Test
    fun `reports display content changed when translation text changes`() {
        val store = AppleNativeOnlineTranslationStore()
        val first = song(
            id = "100",
            RichLyricLine(
                begin = 1_000,
                end = 2_000,
                text = "Hello world",
                translation = "你好，世界",
            ),
        )
        assertReceipt(store, first, updated = true, displayChanged = true, revision = 1)

        val changed = song(
            id = "100",
            RichLyricLine(
                begin = 1_000,
                end = 2_000,
                text = "Hello world",
                translation = "你好世界",
            ),
        )
        assertReceipt(store, changed, updated = true, displayChanged = true, revision = 2)
    }

    @Test
    fun `receipts detect each displayed line dimension through the receive path`() {
        val firstLine = RichLyricLine(
            begin = 1_000, end = 2_000, text = "君の名は",
            translation = "你的名字", roma = "Kimi no na wa",
        )
        listOf(
            firstLine.copy(begin = 1_100),
            firstLine.copy(end = 2_100),
            firstLine.copy(text = "君の名前は"),
            firstLine.copy(translation = "你的姓名"),
            firstLine.copy(roma = "Kimi no namae wa"),
        ).forEach { changedLine ->
            val store = AppleNativeOnlineTranslationStore()
            assertReceipt(store, song("100", firstLine), true, true, 1)
            assertReceipt(store, song("100", changedLine), true, true, 2)
            assertReceipt(store, song("100", changedLine), false, false, 2)
        }
    }

    @Test
    fun `empty or unidentified receipts preserve the accepted overlay and revision`() {
        val store = AppleNativeOnlineTranslationStore()
        val first = song("100", RichLyricLine(
            begin = 1_000, end = 2_000, text = "Hello", translation = "你好",
        ))
        assertReceipt(store, Song(id = "100"), false, false, 0, current = false)
        assertReceipt(store, first, true, true, 1)
        assertReceipt(store, Song(id = "100"), false, false, 1)
        assertReceipt(store, Song(id = "200"), false, false, 1, current = false)
        assertReceipt(store, first.copy(id = null), false, false, 1, current = false)
        assertReceipt(store, first.copy(id = " "), false, false, 1, current = false)
        assertEquals("你好", store.translation("100", 1_000, 2_000, "Hello"))
        assertTrue(store.isCurrentRevision("100", 1))
    }

    @Test
    fun `song switches advance receipt identity even with identical displayed content`() {
        val store = AppleNativeOnlineTranslationStore()
        val first = song("100", RichLyricLine(
            begin = 1_000, end = 2_000, text = "Hello", translation = "你好",
        ))
        assertReceipt(store, first, true, true, 1)
        assertReceipt(store, first.copy(id = "200"), true, true, 2)
        assertFalse(store.isCurrentRevision("100", 1))
        assertFalse(store.hasTranslation("100"))
        assertReceipt(store, first, true, true, 3)
        assertFalse(store.isCurrentRevision("200", 2))
        assertFalse(store.clear("200"))
        assertEquals(3L, store.revision())
        assertTrue(store.clear("100"))
        assertEquals(4L, store.revision())
        assertFalse(store.clear("100"))
        assertReceipt(store, first, true, true, 5)
    }

    @Test
    fun `pronunciation source only receipt refreshes menus without requesting presentation`() {
        val store = AppleNativeOnlineTranslationStore()
        val first = song("100", RichLyricLine(
            begin = 1_000, end = 2_000, text = "君の名は", roma = "Kimi no na wa",
        )).copy(metadata = lyricMetadataOf(LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE to "QM"))
        assertReceipt(store, first, true, true, 1)
        val changed = first.copy(metadata = lyricMetadataOf(
            LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE to "NE",
        ))
        assertReceipt(store, changed, true, false, 1)
        assertEquals("NE", store.pronunciationSource("100"))
        assertFalse(store.hasTranslation("100"))
    }

    @Test
    fun `AI phonetics do not appear before AI response and survive provider refresh`() {
        val store = AppleNativeOnlineTranslationStore()
        val online = song("100", RichLyricLine(
            begin = 1000, end = 2000, text = "君の名は",
            translation = "你的名字", roma = "키미노 나와아",
        ))
        store.receive(online)
        assertTrue(store.hasHangulPronunciation("100"))
        assertFalse(store.hasAiHangulPronunciation("100"))
        assertNull(store.aiHangulPronunciation("100", 1000, 2000, "君の名は"))

        val ai = song("100", RichLyricLine(
            begin = 1000, end = 2000, text = "君の名は",
            translation = "너의 이름은", roma = "키미노 나와",
        ))
        store.receive(ai, preserveExistingPronunciation = true)
        assertTrue(store.hasAiHangulPronunciation("100"))
        assertEquals("키미노 나와", store.aiHangulPronunciation("100", 1000, 2000, "君の名は"))
        assertEquals("너의 이름은", store.aiTranslation("100", 1000, 2000, "君の名は"))

        // Auto provider updates must not replace separately pinned AI result.
        store.receive(online)
        assertEquals("키미노 나와", store.aiHangulPronunciation("100", 1000, 2000, "君の名は"))
        assertEquals("너의 이름은", store.aiTranslation("100", 1000, 2000, "君の名は"))
        store.receive(online.copy(id = "200"))
        assertFalse(store.hasAiHangulPronunciation("100"))
        assertNull(store.aiTranslation("100", 1000, 2000, "君の名は"))
    }

    @Test
    fun `AI translation without generated pronunciation does not label provider as AI`() {
        val store = AppleNativeOnlineTranslationStore()
        store.receive(song("100", RichLyricLine(
            begin = 1000, end = 2000, text = "君の名は", roma = "키미노 나와아",
        )))
        store.receive(song("100", RichLyricLine(
            begin = 1000, end = 2000, text = "君の名は",
            translation = "너의 이름은", roma = null,
        )), preserveExistingPronunciation = true)
        assertTrue(store.hasPronunciation("100"))
        assertFalse(store.hasAiHangulPronunciation("100"))
        assertNull(store.aiHangulPronunciation("100", 1000, 2000, "君の名は"))
        assertEquals("너의 이름은", store.aiTranslation("100", 1000, 2000, "君の名は"))
    }

    private fun assertReceipt(
        store: AppleNativeOnlineTranslationStore,
        song: Song,
        updated: Boolean,
        displayChanged: Boolean,
        revision: Long,
        current: Boolean = true,
    ) {
        val receipt = store.receive(song)
        assertEquals(updated, receipt.updated)
        assertEquals(displayChanged, receipt.displayContentChanged)
        assertEquals(AppleLyricsPresentationUpdate(song.id, revision), receipt.update)
        assertEquals(revision, store.revision())
        assertEquals(current, store.isCurrentRevision(receipt.update.songId, checkNotNull(receipt.update.revision)))
    }

    private fun song(id: String, vararg lines: RichLyricLine) = Song(
        id = id,
        lyrics = lines.toList(),
    )

    @Test
    fun `standalone AI translation preserves previously available pronunciation`() {
        val store = AppleNativeOnlineTranslationStore()
        store.receive(
            Song(
                id = "100",
                metadata = lyricMetadataOf(
                    LyricMetadataKeys.ONLINE_PRONUNCIATION_SOURCE to "NE",
                ),
                lyrics = listOf(RichLyricLine(
                    begin = 1_000, end = 2_000, text = "君の名は", roma = "Kimi no na wa",
                )),
            )
        )

        store.receive(
            song("100", RichLyricLine(
                begin = 1_000, end = 2_000, text = "君の名は",
                translation = "너의 이름은", roma = "AI imperfect romaji",
            )),
            preserveExistingPronunciation = true,
        )
        assertEquals("너의 이름은", store.translation("100", 1_000, 2_000, "君の名は"))
        assertEquals("Kimi no na wa", store.pronunciation("100", 1_000, 2_000, "君の名は"))
        assertEquals("NE", store.pronunciationSource("100"))
    }

    @Test
    fun `normal source changes still replace pronunciation and AI cannot leak across songs`() {
        val store = AppleNativeOnlineTranslationStore()
        store.update(song("100", RichLyricLine(
            begin = 1_000, end = 2_000, text = "君の名は", roma = "Kimi no na wa",
        )))
        store.receive(song("100", RichLyricLine(
            begin = 1_000, end = 2_000, text = "君の名は", translation = "너의 이름은",
        )))
        assertFalse(store.hasPronunciation("100"))

        store.update(song("100", RichLyricLine(
            begin = 1_000, end = 2_000, text = "君の名は", roma = "Kimi no na wa",
        )))
        store.receive(song("200", RichLyricLine(
            begin = 1_000, end = 2_000, text = "君の名は", translation = "다른 곡",
        )), preserveExistingPronunciation = true)
        assertFalse(store.hasPronunciation("200"))
        assertNull(store.pronunciation("200", 1_000, 2_000, "君の名は"))
    }

    @Test
    fun `standalone AI Hangul overrides displayed Latin romaji but keeps other lines`() {
        val store = AppleNativeOnlineTranslationStore()
        store.update(song("ja", RichLyricLine(
            begin = 1_000, end = 2_000, text = "君の名は",
            roma = "Kimi no na wa",
        ), RichLyricLine(
            begin = 2_000, end = 3_000, text = "ありがとう",
            roma = "Arigatou",
        )))
        store.receive(song("ja", RichLyricLine(
            begin = 1_000, end = 2_000, text = "君の名は",
            translation = "너의 이름은", roma = "키미노 나와",
        ), RichLyricLine(
            begin = 2_000, end = 3_000, text = "ありがとう",
            translation = "고마워",
        )), preserveExistingPronunciation = true)
        assertEquals("키미노 나와", store.pronunciation("ja", 1_000, 2_000, "君の名は"))
        assertEquals("Arigatou", store.pronunciation("ja", 2_000, 3_000, "ありがとう"))
        assertTrue(store.hasPronunciation("ja"))
    }

    @Test
    fun `store exposes validated Korean-script pronunciation for English lines`() {
        val store = AppleNativeOnlineTranslationStore()
        store.update(song("english", RichLyricLine(
            begin = 1_000, end = 2_000, text = "I love you",
            translation = "사랑해", roma = "아이 러브 유",
        )))
        assertTrue(store.hasPronunciation("english"))
        assertEquals("아이 러브 유", store.pronunciation("english", 1_000, 2_000, "I love you"))
    }
}
