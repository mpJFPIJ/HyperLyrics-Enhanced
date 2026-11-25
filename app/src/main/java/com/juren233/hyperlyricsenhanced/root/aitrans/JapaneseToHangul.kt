package com.juren233.hyperlyricsenhanced.root.aitrans

/** Conservative kana-to-Hangul transcription. Unknown scripts are never guessed. */
internal object JapaneseToHangul {
    private val base = mapOf(
        'あ' to "아", 'い' to "이", 'う' to "우", 'え' to "에", 'お' to "오",
        'か' to "카", 'き' to "키", 'く' to "쿠", 'け' to "케", 'こ' to "코",
        'さ' to "사", 'し' to "시", 'す' to "스", 'せ' to "세", 'そ' to "소",
        'た' to "타", 'ち' to "치", 'つ' to "츠", 'て' to "테", 'と' to "토",
        'な' to "나", 'に' to "니", 'ぬ' to "누", 'ね' to "네", 'の' to "노",
        'は' to "하", 'ひ' to "히", 'ふ' to "후", 'へ' to "헤", 'ほ' to "호",
        'ま' to "마", 'み' to "미", 'む' to "무", 'め' to "메", 'も' to "모",
        'や' to "야", 'ゆ' to "유", 'よ' to "요",
        'ら' to "라", 'り' to "리", 'る' to "루", 'れ' to "레", 'ろ' to "로",
        'わ' to "와", 'を' to "오",
        'が' to "가", 'ぎ' to "기", 'ぐ' to "구", 'げ' to "게", 'ご' to "고",
        'ざ' to "자", 'じ' to "지", 'ず' to "즈", 'ぜ' to "제", 'ぞ' to "조",
        'だ' to "다", 'ぢ' to "지", 'づ' to "즈", 'で' to "데", 'ど' to "도",
        'ば' to "바", 'び' to "비", 'ぶ' to "부", 'べ' to "베", 'ぼ' to "보",
        'ぱ' to "파", 'ぴ' to "피", 'ぷ' to "푸", 'ぺ' to "페", 'ぽ' to "포",
        'ゔ' to "브", 'ぁ' to "아", 'ぃ' to "이", 'ぅ' to "우", 'ぇ' to "에", 'ぉ' to "오",
    )

    private val combos = mapOf(
        "きゃ" to "캬", "きゅ" to "큐", "きょ" to "쿄",
        "ぎゃ" to "갸", "ぎゅ" to "규", "ぎょ" to "교",
        "しゃ" to "샤", "しゅ" to "슈", "しょ" to "쇼",
        "じゃ" to "자", "じゅ" to "주", "じょ" to "조",
        "ちゃ" to "차", "ちゅ" to "추", "ちょ" to "초",
        "にゃ" to "냐", "にゅ" to "뉴", "にょ" to "뇨",
        "ひゃ" to "햐", "ひゅ" to "휴", "ひょ" to "효",
        "びゃ" to "뱌", "びゅ" to "뷰", "びょ" to "뵤",
        "ぴゃ" to "퍄", "ぴゅ" to "퓨", "ぴょ" to "표",
        "みゃ" to "먀", "みゅ" to "뮤", "みょ" to "묘",
        "りゃ" to "랴", "りゅ" to "류", "りょ" to "료",
        "てぃ" to "티", "でぃ" to "디", "とぅ" to "투", "どぅ" to "두",
        "ふぁ" to "파", "ふぃ" to "피", "ふぇ" to "페", "ふぉ" to "포",
        "うぃ" to "위", "うぇ" to "웨", "うぉ" to "워",
        "しぇ" to "셰", "ちぇ" to "체", "じぇ" to "제",
        "つぁ" to "차", "つぃ" to "치", "つぇ" to "체", "つぉ" to "초",
        "ふゅ" to "퓨", "ゔぁ" to "바", "ゔぃ" to "비", "ゔぇ" to "베", "ゔぉ" to "보",
    )

    fun fromKana(reading: String): String? {
        if (reading.isBlank()) return null
        val normalized = buildString(reading.length) {
            reading.forEach { c ->
                append(if (c in 'ァ'..'ヶ') (c.code - 0x60).toChar() else c)
            }
        }
        val output = StringBuilder()
        var index = 0
        var previousO = false
        while (index < normalized.length) {
            val current = normalized[index]
            when {
                current.isWhitespace() || current in "、。，．!?！？・/()[]「」『』" -> {
                    if (output.isNotEmpty() && output.last() != ' ') output.append(' ')
                    previousO = false
                    index++
                }
                current == 'ー' -> { // Japanese vowel length is not doubled in natural Hangul transcription.
                    index++
                }
                current == 'っ' -> {
                    if (!appendFinal(output, when (normalized.getOrNull(index + 1)) {
                        'か', 'き', 'く', 'け', 'こ', 'が', 'ぎ', 'ぐ', 'げ', 'ご' -> 1
                        'ぱ', 'ぴ', 'ぷ', 'ぺ', 'ぽ', 'ば', 'び', 'ぶ', 'べ', 'ぼ' -> 17
                        else -> 19
                    })) return null
                    previousO = false
                    index++
                }
                current == 'ん' -> {
                    if (!appendFinal(output, 4)) return null
                    previousO = false
                    index++
                }
                current == 'う' && previousO -> {
                    previousO = false
                    index++
                }
                else -> {
                    val pair = normalized.substring(index, (index + 2).coerceAtMost(normalized.length))
                    val combined = combos[pair]
                    val hangul = combined ?: base[current] ?: return null
                    output.append(hangul)
                    previousO = (if (combined != null) pair[1] else current) in "おこそとのほもよろをごぞどぼぽょ"
                    index += if (combined != null) 2 else 1
                }
            }
        }
        return output.toString().trim().takeIf(String::isNotBlank)
    }

    private fun appendFinal(output: StringBuilder, finalIndex: Int): Boolean {
        if (output.isEmpty()) return false
        val last = output.last()
        if (last !in '가'..'힣') return false
        val syllable = last.code - 0xAC00
        if (syllable % 28 != 0) return false
        output.setCharAt(output.lastIndex, (last.code + finalIndex).toChar())
        return true
    }
}
