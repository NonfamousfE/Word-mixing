package com.wordmix.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 单词混记 · C-S 架构标准 RESTful API 客户端。
 *
 * 替代旧版复杂的 Oplog 同步引擎，直接通过标准 HTTP 接口与集中式后端交互。
 * 兼容纯 JVM 环境，方便单元测试与真机运行。
 */
class ApiClient(
    var baseUrl: String = "http://127.0.0.1:8000/api",
    var token: String = ""
) {

    private fun request(
        method: String,
        path: String,
        bodyJson: String? = null
    ): Pair<Int, String> {
        val cleanBase = baseUrl.trimEnd('/')
        val url = URL("$cleanBase$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (bodyJson != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        if (bodyJson != null) {
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(bodyJson) }
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, "UTF-8")).use { reader -> reader.readText() }
        } ?: ""

        return Pair(code, text)
    }

    // --- 认证 ---

    fun login(user: String, pass: String): Boolean {
        val payload = AppJson.write(mutableMapOf("username" to user, "password" to pass))
        val (code, text) = request("POST", "/auth/login", payload)
        if (code == 200) {
            val obj = AppJson.parseObject(text)
            token = AppJson.str(obj, "access_token")
            return true
        }
        return false
    }

    fun register(user: String, pass: String): Boolean {
        val payload = AppJson.write(mutableMapOf("username" to user, "password" to pass))
        val (code, text) = request("POST", "/auth/register", payload)
        if (code == 200) {
            val obj = AppJson.parseObject(text)
            token = AppJson.str(obj, "access_token")
            return true
        }
        return false
    }

    // --- 分组与词条 ---

    @JvmOverloads
    @Suppress("UNCHECKED_CAST")
    fun getGroups(query: String? = null, all: Boolean = true): List<MutableMap<String, Any?>> {
        val params = mutableListOf<String>()
        if (all) params.add("all=true")
        if (!query.isNullOrBlank()) {
            params.add("q=" + URLEncoder.encode(query, "UTF-8"))
        }
        val queryStr = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
        val (code, text) = request("GET", "/groups$queryStr")
        if (code == 200) {
            val raw = AppJson.parse(text)
            if (raw is MutableMap<*, *>) {
                val items = raw["items"]
                if (items is List<*>) {
                    return items.filterIsInstance<MutableMap<String, Any?>>()
                }
            } else if (raw is List<*>) {
                return raw.filterIsInstance<MutableMap<String, Any?>>()
            }
        }
        return emptyList()
    }

    fun createGroup(name: String, note: String = ""): MutableMap<String, Any?>? {
        val payload = AppJson.write(mutableMapOf("name" to name, "note" to note))
        val (code, text) = request("POST", "/groups", payload)
        return if (code == 200) AppJson.parseObject(text) else null
    }

    fun updateGroup(id: String, name: String? = null, note: String? = null): MutableMap<String, Any?>? {
        val m = mutableMapOf<String, Any?>()
        if (name != null) m["name"] = name
        if (note != null) m["note"] = note
        val (code, text) = request("PUT", "/groups/$id", AppJson.write(m))
        return if (code == 200) AppJson.parseObject(text) else null
    }

    fun deleteGroup(id: String): Boolean {
        val (code, _) = request("DELETE", "/groups/$id")
        return code == 200
    }

    fun createEntry(
        groupId: String,
        word: String,
        meaning: String = "",
        note: String = "",
        phonetic: String = ""
    ): MutableMap<String, Any?>? {
        val payload = AppJson.write(
            mutableMapOf(
                "group_id" to groupId,
                "word" to word,
                "meaning" to meaning,
                "note" to note,
                "phonetic" to phonetic
            )
        )
        val (code, text) = request("POST", "/entries", payload)
        return if (code == 200) AppJson.parseObject(text) else null
    }

    fun updateEntry(
        id: String,
        word: String? = null,
        meaning: String? = null,
        note: String? = null,
        phonetic: String? = null
    ): MutableMap<String, Any?>? {
        val m = mutableMapOf<String, Any?>()
        if (word != null) m["word"] = word
        if (meaning != null) m["meaning"] = meaning
        if (note != null) m["note"] = note
        if (phonetic != null) m["phonetic"] = phonetic
        val (code, text) = request("PUT", "/entries/$id", AppJson.write(m))
        return if (code == 200) AppJson.parseObject(text) else null
    }

    fun deleteEntry(id: String): Boolean {
        val (code, _) = request("DELETE", "/entries/$id")
        return code == 200
    }

    fun getPhonetic(word: String): String {
        val path = "/phonetics?word=" + URLEncoder.encode(word, "UTF-8")
        val (code, text) = request("GET", path)
        if (code == 200) {
            val obj = AppJson.parseObject(text)
            return AppJson.str(obj, "ipa")
        }
        return ""
    }
}
