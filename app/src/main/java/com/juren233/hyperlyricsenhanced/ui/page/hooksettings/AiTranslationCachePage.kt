package com.juren233.hyperlyricsenhanced.ui.page.hooksettings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.juren233.hyperlyricsenhanced.root.aitrans.AITranslator
import com.juren233.hyperlyricsenhanced.root.aitrans.AiCachedSong
import com.juren233.hyperlyricsenhanced.ui.component.SimpleDialog
import com.juren233.hyperlyricsenhanced.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference

/** Song cache management is intentionally inside Enhanced, not Apple Music. */
@Composable
fun AiTranslationCachePage() {
    val context = LocalContext.current
    val korean = LocalConfiguration.current.locales.get(0)?.language == "ko"
    val scope = rememberCoroutineScope()
    var songs by remember { mutableStateOf<List<AiCachedSong>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<AiCachedSong?>(null) }

    suspend fun reload() {
        loading = true
        runCatching {
            withContext(Dispatchers.IO) {
                AITranslator.init(context)
                AITranslator.listCachedSongs()
            }
        }.onSuccess { songs = it }
            .onFailure {
                status = if (korean) "캐시 목록을 불러오지 못했어요"
                    else "Could not load saved translations"
            }
        loading = false
    }

    LaunchedEffect(context) { reload() }

    val confirmSong = selected
    SimpleDialog(
        show = confirmSong != null,
        title = if (korean) "곡별 AI 번역 삭제" else "Delete song AI cache",
        summary = if (confirmSong == null) null else {
            val display = confirmSong.title?.ifBlank { null }
                ?: if (korean) "곡명 정보 없는 이전 기록" else "Unidentified older record"
            if (korean) "$display 의 AI 번역·한글 발음 캐시를 삭제할까요? 원본 가사와 다른 곡은 유지됩니다."
            else "Delete $display's AI translation and Hangul phonetics? Other songs and original lyrics remain."
        },
        confirmText = if (korean) "삭제" else "Delete",
        onDismiss = { selected = null },
        onConfirm = {
            val target = confirmSong ?: return@SimpleDialog
            if (busy) return@SimpleDialog
            busy = true
            scope.launch {
                val removed = runCatching {
                    withContext(Dispatchers.IO) {
                        AITranslator.deleteCachedSong(target.groupId)
                    }
                }.getOrDefault(false)
                status = if (removed) {
                    if (korean) "삭제했어요. 재생 중 표시된 가사는 다음 재생 때 갱신될 수 있어요."
                    else "Deleted. Currently displayed lyrics may refresh on the next playback."
                } else {
                    if (korean) "삭제하지 못했어요. 다시 시도해 주세요."
                    else "Could not delete this cache. Please retry."
                }
                busy = false
                reload()
            }
        },
    )

    XposedLyricSettingPage(
        title = if (korean) "곡별 AI 번역 관리" else "Saved AI translations",
    ) {
        item(key = "cached_songs_intro") {
            SmallTitle(
                text = if (korean)
                    "HyperLyrics에 저장된 번역·발음 기록을 곡별로 관리합니다. 이전 버전의 기록은 곡을 다시 재생하면 이름이 표시됩니다."
                else "Manage each song's cached translation and phonetics. Older records receive song names after being played again.",
            )
        }
        item(key = "cached_songs_refresh") {
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).fillMaxWidth()) {
                ArrowPreference(
                    title = if (korean) "목록 새로고침" else "Refresh list",
                    onClick = { if (!busy) scope.launch { reload() } },
                )
            }
        }
        if (loading || songs.isEmpty()) {
            item(key = "cached_songs_empty") {
                Text(
                    text = when {
                        loading -> if (korean) "불러오는 중…" else "Loading…"
                        else -> if (korean) "저장된 AI 번역이 없어요" else "No saved AI translations"
                    },
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
        status?.let { message ->
            item(key = "cached_songs_status") {
                Text(text = message, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
        }
        songs.forEach { song ->
            item(key = "cached_song_${song.groupId}") {
                val display = song.title?.ifBlank { null } ?: if (korean)
                    "이전 기록 · ${song.groupId.removePrefix("legacy:").take(8)}"
                else "Older record · ${song.groupId.removePrefix("legacy:").take(8)}"
                val artist = song.artist?.takeIf(String::isNotBlank)
                    ?: if (song.isLegacy) {
                        if (korean) "곡명 확인 불가 · 이 기록만 삭제 가능"
                        else "Title unavailable · this record only"
                    } else if (korean) "가수 정보 없음" else "Unknown artist"
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp).fillMaxWidth()) {
                    ArrowPreference(
                        title = display,
                        summary = artist,
                        onClick = { if (!busy) selected = song },
                    )
                }
            }
        }
    }
}
