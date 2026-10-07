package com.wordmix.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 相似词推荐算法：从桌面端 app/wordmix/data.py 1:1 移植过来。
 *
 * 核心目的：
 *   用户的词库是"一个分组 = 一组易混词"。
 *   加词时输入单词，自动推荐可能混淆的现有词，点一下并入那一组。
 */
object Similarity {

    fun normalizeWordKey(word: String?): String {
        if (word == null) return ""
        return word.lowercase()
            .replace("’", "'")
            .replace("‘", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val ca = a[i - 1]
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cb = b[j - 1]
                val cost = if (ca == cb) 0 else 1
                cur[j] = minOf(
                    prev[j] + 1,       // deletion
                    cur[j - 1] + 1,    // insertion
                    prev[j - 1] + cost // substitution
                )
            }
            prev = cur
        }
        return prev[b.length]
    }

    fun commonPrefixLen(a: String, b: String): Int {
        val n = min(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) {
            i++
        }
        return i
    }

    fun commonSuffixLen(a: String, b: String): Int {
        val n = min(a.length, b.length)
        var i = 0
        while (i < n && a[a.length - 1 - i] == b[b.length - 1 - i]) {
            i++
        }
        return i
    }

    /**
     * 0..1 的相似度，专门为"易混词"调过：
     * 易混词通常是首字母相同 + 长度接近（plague/plight/plough），
     * 或者只差一两个字母（metal/motel、though/through）。
     */
    fun similarity(a: String, b: String): Double {
        val na = normalizeWordKey(a)
        val nb = normalizeWordKey(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0

        val dist = levenshtein(na, nb)
        val longest = max(na.length, nb.length).toDouble()
        val editScore = 1.0 - dist / longest
        val pre = commonPrefixLen(na, nb) / longest
        val suf = commonSuffixLen(na, nb) / longest
        val firstBonus = if (na[0] == nb[0]) 0.18 else 0.0

        val score = 0.55 * editScore + 0.25 * pre + 0.10 * suf + firstBonus
        return max(0.0, min(1.0, score))
    }

    class Candidate(
        val entry: Map<String, Any?>,
        val score: Double,
        val groupId: String,
        val groupName: String,
        val siblings: List<Map<String, Any?>>,
    )

    /**
     * 给一个新词找出"可能想并入"的现有词条，按相似度从高到低。
     */
    fun similarEntries(
        lib: Library,
        word: String,
        limit: Int = 8,
        minScore: Double = 0.34
    ): List<Candidate> {
        val key = normalizeWordKey(word)
        if (key.isEmpty()) return emptyList()

        val aliveGroups = lib.aliveGroups().associateBy { Items.id(it) }
        val aliveEntries = lib.aliveEntries()

        val byGroup = mutableMapOf<String, MutableList<Map<String, Any?>>>()
        for (e in aliveEntries) {
            val gid = AppJson.str(e, "groupId")
            byGroup.getOrPut(gid) { mutableListOf() }.add(e)
        }

        val scored = mutableListOf<Pair<Double, Map<String, Any?>>>()
        for (e in aliveEntries) {
            val other = normalizeWordKey(Items.word(e))
            if (other.isEmpty()) continue
            var s = similarity(key, other)
            if (commonPrefixLen(key, other) >= 3) {
                s = max(s, 0.4)
            }
            if (s < minScore) continue
            scored.add(Pair(s, e))
        }

        scored.sortByDescending { it.first }

        val out = mutableListOf<Candidate>()
        for ((s, e) in scored) {
            val gid = AppJson.str(e, "groupId")
            val g = aliveGroups[gid]
            val siblings = byGroup[gid] ?: emptyList()
            out.add(
                Candidate(
                    entry = e,
                    score = ((s * 1000).roundToInt()) / 1000.0,
                    groupId = gid,
                    groupName = if (g != null) AppJson.str(g, "name") else "未分组",
                    siblings = siblings
                )
            )
            if (out.size >= limit) break
        }
        return out
    }
}
