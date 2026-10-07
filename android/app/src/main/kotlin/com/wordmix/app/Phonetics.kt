package com.wordmix.app

import java.io.File
import java.util.zip.GZIPInputStream

/**
 * 音标：把 CMU 发音词典的 ARPAbet 转成 IPA。
 *
 * 词典数据和桌面端**是同一份**（app/wordmix/data/phonetic.txt.gz），
 * 由 build/make-android-assets.py 拷进 assets。这样两端音标完全一致，
 * 不会出现"同一个词电脑和手机音标不一样"。
 *
 * 转换规则也要和桌面端一致（重音位置、英式非儿化等），
 * 否则同一个词两边显示不同 —— 所以这里逐条对着 app/wordmix/phonetics.py 写。
 */
object Phonetics {

    private var table: Map<String, String>? = null
    private var loaded = false

    /**
     * 从 assets 或文件加载词典。
     *
     * @param open 惰性打开数据流（安卓上是 assets.open，测试里是文件）
     */
    fun load(open: () -> java.io.InputStream) {
        if (loaded) return
        loaded = true
        table = try {
            val bis = java.io.BufferedInputStream(open())
            bis.mark(2)
            val b1 = bis.read()
            val b2 = bis.read()
            bis.reset()
            val stream: java.io.InputStream = if (b1 == 0x1f && b2 == 0x8b) {
                GZIPInputStream(bis)
            } else {
                bis
            }
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                val m = HashMap<String, String>()
                for (line in lines) {
                    val i = line.indexOf('\t')
                    if (i > 0) m[line.substring(0, i)] = line.substring(i + 1)
                }
                m
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun isLoaded(): Boolean = loaded && !(table ?: emptyMap()).isEmpty()

    fun wordCount(): Int = (table ?: emptyMap()).size

    // ARPAbet → IPA（美式基础）
    private val IPA_US = mapOf(
        "AA" to "ɑ", "AE" to "æ", "AH" to "ʌ", "AO" to "ɔ", "AW" to "aʊ", "AY" to "aɪ",
        "EH" to "ɛ", "ER" to "ɝ", "EY" to "eɪ", "IH" to "ɪ", "IY" to "i", "OW" to "oʊ",
        "OY" to "ɔɪ", "UH" to "ʊ", "UW" to "u",
        "B" to "b", "CH" to "tʃ", "D" to "d", "DH" to "ð", "F" to "f", "G" to "ɡ",
        "HH" to "h", "JH" to "dʒ", "K" to "k", "L" to "l", "M" to "m", "N" to "n",
        "NG" to "ŋ", "P" to "p", "R" to "ɹ", "S" to "s", "SH" to "ʃ", "T" to "t",
        "TH" to "θ", "V" to "v", "W" to "w", "Y" to "j", "Z" to "z", "ZH" to "ʒ",
    )

    private val IPA_UK_OVERRIDE = mapOf(
        "ER" to "ɜː", "AA" to "ɒ", "AO" to "ɔː", "IY" to "iː", "UW" to "uː",
    )

    private val VOWELS = setOf(
        "AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW"
    )
    private val UK_LONG = setOf("AA", "AO", "IY", "UW", "ER")
    private val UK_UNSTRESSED = mapOf("AA" to "ə", "AO" to "ə", "IY" to "i", "UW" to "u", "ER" to "ə")

    /** 返回 /.../ 形式的英式 IPA；查不到返回空串。 */
    fun ipa(word: String, accent: String = "uk"): String {
        val w = clean(word)
        if (w.isEmpty()) return ""
        val pron = lookup(w) ?: return ""
        return "/" + toIpa(pron, accent) + "/"
    }

    private fun lookup(w: String): String? {
        val t = table ?: return null
        t[w]?.let { return it }
        // 英美拼写差异归一（和桌面端一致）
        val alt = when {
            w.endsWith("our") -> w.dropLast(3) + "or"
            w.endsWith("ise") -> w.dropLast(3) + "ize"
            w.endsWith("isation") -> w.dropLast(7) + "ization"
            w.endsWith("re") && w.length > 3 -> w.dropLast(2) + "er"
            w.endsWith("lled") -> w.dropLast(4) + "led"
            else -> w
        }
        return t[alt]
    }

    private fun clean(s: String): String {
        val sb = StringBuilder()
        for (c in s.trim().lowercase()) {
            if (c in 'a'..'z' || c == '\'' || c == '-' || c == '.') sb.append(c)
        }
        return sb.toString()
    }

    /** 去掉"紧邻主重音之前的那个次重音"（CMUdict 对 impose 这类词会多标一个）。 */
    private fun dropSpuriousStress(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        for (i in tokens.indices) {
            val m = Regex("^([A-Z]+)([0-2])?$").find(tokens[i])
            if (m == null) { out.add(tokens[i]); continue }
            val phon = m.groupValues[1]
            val stress = m.groupValues[2]
            if (stress == "2" && VOWELS.contains(phon)) {
                var j = i + 1
                var nextStress: String? = null
                while (j < tokens.size) {
                    val mm = Regex("^([A-Z]+)([0-2])?$").find(tokens[j])
                    if (mm != null && VOWELS.contains(mm.groupValues[1])) {
                        nextStress = mm.groupValues[2]; break
                    }
                    if (mm != null && (mm.groupValues[2] == "1" || mm.groupValues[2] == "2")) {
                        nextStress = mm.groupValues[2]; break
                    }
                    j++
                }
                if (nextStress == "1") {
                    var between = false
                    for (k in i + 1 until j) {
                        val mk = Regex("^([A-Z]+)([0-2])?$").find(tokens[k])
                        if (mk != null && VOWELS.contains(mk.groupValues[1])) { between = true; break }
                    }
                    if (!between) { out.add(phon); continue }
                }
            }
            out.add(tokens[i])
        }
        return out
    }

    private fun toIpa(pron: String, accent: String): String {
        val tokens = dropSpuriousStress(pron.split(" ").filter { it.isNotEmpty() })

        data class Piece(val sym: String, val phon: String, val mark: String)

        val pieces = ArrayList<Piece>()
        for (i in tokens.indices) {
            val m = Regex("^([A-Z]+)([0-2])?$").find(tokens[i]) ?: continue
            val phon = m.groupValues[1]
            val stress = m.groupValues[2].ifEmpty { "0" }
            val isVowel = VOWELS.contains(phon)

            if (isVowel) {
                var sym: String
                if (accent == "uk") {
                    sym = if (stress == "0" && phon == "AH") "ə"
                    else {
                        val base = IPA_UK_OVERRIDE[phon] ?: IPA_US[phon] ?: phon.lowercase()
                        if (stress == "0" && UK_LONG.contains(phon))
                            UK_UNSTRESSED[phon] ?: base
                        else base
                    }
                } else {
                    sym = IPA_US[phon] ?: phon.lowercase()
                    if (stress == "0" && phon == "AH") sym = "ə"
                }
                val mark = when (stress) {
                    "1" -> "ˈ"; "2" -> "ˌ"; else -> ""
                }
                pieces.add(Piece(sym, phon, mark))
            } else {
                if (accent == "uk" && phon == "R") {
                    // 英式非儿化：元音后的 R 不发音
                    var prevVowel = false
                    for (j in i - 1 downTo 0) {
                        val pm = Regex("^([A-Z]+)").find(tokens[j])
                        if (pm != null) { prevVowel = VOWELS.contains(pm.groupValues[1]); break }
                    }
                    if (prevVowel) continue
                }
                pieces.add(Piece(IPA_US[phon] ?: phon.lowercase(), phon, ""))
            }
        }

        // 重音标记要放在**整个音节**前面（含音节开头的辅音），
        // 直接贴在元音前会写成 plˈeɪɡ 这种错形式。
        val marksAt = HashMap<Int, MutableSet<String>>()
        for (idx in pieces.indices) {
            val mark = pieces[idx].mark
            if (mark.isEmpty()) continue
            var pos = idx
            var j = idx - 1
            while (j >= 0 && !VOWELS.contains(pieces[j].phon)) { pos = j; j-- }
            marksAt.getOrPut(pos) { mutableSetOf() }.add(mark)
        }

        val sb = StringBuilder()
        for (idx in pieces.indices) {
            val marks = marksAt[idx]
            if (marks != null) sb.append(if (marks.contains("ˈ")) "ˈ" else "ˌ")
            sb.append(pieces[idx].sym)
        }
        return sb.toString()
    }
}
