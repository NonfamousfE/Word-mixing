package com.wordmix.app

/**
 * 极简 JSON 读写。
 *
 * 为什么自己写而不直接用 org.json：
 *   org.json 是 Android 框架的一部分，在普通 JVM 单元测试里没有，
 *   那样同步逻辑就没法在电脑上离线测 —— 而同步正是最需要测的部分。
 *   自己实现一份，同一套代码在 Android 和 JVM 上都能跑。
 *
 * 只实现我们真正需要的部分：对象、数组、字符串、数字、布尔、null。
 * 不追求完整 JSON 规范（比如不做 \u 之外的转义），够用且好验证。
 */
object AppJson {

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): MutableMap<String, Any?> =
        (parse(text) as? MutableMap<String, Any?>) ?: mutableMapOf()

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): Any? {
            skipWs()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> num()
            }
        }

        private fun expect(word: String) {
            if (s.startsWith(word, i)) i += word.length
            else throw IllegalArgumentException("位置 $i 期望 $word")
        }

        private fun obj(): MutableMap<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++                       // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (i < s.length) {
                skipWs()
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("位置 $i 期望 :")
                i++
                m[k] = value()
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; break }
                throw IllegalArgumentException("位置 $i 期望 , 或 }")
            }
            return m
        }

        private fun arr(): MutableList<Any?> {
            val a = ArrayList<Any?>()
            i++                       // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (i < s.length) {
                a.add(value())
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; break }
                throw IllegalArgumentException("位置 $i 期望 , 或 ]")
            }
            return a
        }

        private fun str(): String {
            if (i >= s.length || s[i] != '"') throw IllegalArgumentException("位置 $i 期望字符串")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c == '\\' -> {
                        i++
                        if (i >= s.length) break
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 < s.length) {
                                    val hex = s.substring(i + 1, i + 5)
                                    sb.append(hex.toInt(16).toChar())
                                    i += 4
                                }
                            }
                            else -> sb.append(e)
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            throw IllegalArgumentException("字符串没有结束引号")
        }

        private fun num(): Any {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                        s[i] == '-' || s[i] == '+')) i++
            val t = s.substring(start, i)
            return if (t.contains('.') || t.contains('e') || t.contains('E')) {
                t.toDouble()
            } else {
                t.toLongOrNull() ?: t.toDouble()
            }
        }
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    fun write(v: Any?): String {
        val sb = StringBuilder()
        writeTo(sb, v)
        return sb.toString()
    }

    /** 便于阅读的写法（缩进 2 空格），用来存本地文件。 */
    fun writePretty(v: Any?, indent: Int = 0): String {
        val sb = StringBuilder()
        writePrettyTo(sb, v, indent)
        return sb.toString()
    }

    private fun writeTo(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int -> sb.append(v.toString())
            is Long -> sb.append(v.toString())
            is Double ->
                if (v == v.toLong().toDouble()) sb.append(v.toLong().toString())
                else sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    writeTo(sb, value)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, item)
                }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun writePrettyTo(sb: StringBuilder, v: Any?, indent: Int) {
        val pad = "  ".repeat(indent)
        val padIn = "  ".repeat(indent + 1)
        when (v) {
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(padIn)
                    quote(sb, k.toString())
                    sb.append(": ")
                    writePrettyTo(sb, value, indent + 1)
                }
                sb.append('\n').append(pad).append('}')
            }
            is List<*> -> {
                if (v.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                var first = true
                for (item in v) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(padIn)
                    writePrettyTo(sb, item, indent + 1)
                }
                sb.append('\n').append(pad).append(']')
            }
            else -> writeTo(sb, v)
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code))
                    else sb.append(c)
            }
        }
        sb.append('"')
    }

    // ------------------------------------------------------------------
    // 取值辅助（解析结果都是 Any?，用这些少写点类型转换）
    // ------------------------------------------------------------------

    fun str(m: Map<String, Any?>?, key: String, def: String = ""): String {
        val v = m?.get(key) ?: return def
        return if (v is String) v else def
    }

    fun num(m: Map<String, Any?>?, key: String, def: Long = 0): Long {
        val v = m?.get(key) ?: return def
        return when (v) {
            is Long -> v
            is Int -> v.toLong()
            is Double -> v.toLong()
            is String -> v.toLongOrNull() ?: def
            else -> def
        }
    }

    fun bool(m: Map<String, Any?>?, key: String, def: Boolean = false): Boolean {
        val v = m?.get(key) ?: return def
        return v as? Boolean ?: def
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(m: Map<String, Any?>?, key: String): MutableMap<String, Any?>? =
        m?.get(key) as? MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun list(m: Map<String, Any?>?, key: String): MutableList<Any?> =
        (m?.get(key) as? MutableList<Any?>) ?: mutableListOf()}
