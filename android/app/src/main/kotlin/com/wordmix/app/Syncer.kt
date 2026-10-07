package com.wordmix.app

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 与同步服务器通信。
 *
 * 刻意用 JDK 自带的 HttpURLConnection，不引 OkHttp：
 *   OkHttp 在开发机上没缓存，而且这里只有 4 个简单请求，
 *   自带 API 已经够用 —— 更重要的是，这样同步逻辑能在
 *   普通 JVM 单元测试里直接对真实服务器跑，不用装到手机上。
 */

class SyncConfig(
    var url: String = "",
    var token: String = "",
) {
    val configured: Boolean get() = url.isNotBlank() && token.isNotBlank()

    /** 去掉结尾斜杠并确保有 http:// 协议头，避免抛出 no protocol 错误 */
    fun base(): String {
        var u = url.trim().trimEnd('/')
        if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://$u"
        }
        return u
    }
}

class SyncState {
    var deviceId: String = ""
    var deviceName: String = ""
    var lastSeq: Long = 0
    var lastVersion: Long = 0
    var lastSyncAt: String = ""
    var lastError: String = ""
    val pending = mutableListOf<Item>()      // 还没推上去的本地改动
    val seenOps = mutableSetOf<String>()     // 已应用过的操作 id（幂等）

    fun toJson(): String = AppJson.writePretty(mutableMapOf(
        "deviceId" to deviceId,
        "deviceName" to deviceName,
        "lastSeq" to lastSeq,
        "lastVersion" to lastVersion,
        "lastSyncAt" to lastSyncAt,
        "lastError" to lastError,
        "pending" to pending.toMutableList(),
        "seenOps" to seenOps.toMutableList(),
    ))

    companion object {
        fun fromJson(text: String): SyncState {
            val st = SyncState()
            val m = AppJson.parseObject(text)
            st.deviceId = AppJson.str(m, "deviceId")
            st.deviceName = AppJson.str(m, "deviceName")
            st.lastSeq = AppJson.num(m, "lastSeq")
            st.lastVersion = AppJson.num(m, "lastVersion")
            st.lastSyncAt = AppJson.str(m, "lastSyncAt")
            st.lastError = AppJson.str(m, "lastError")
            for (p in AppJson.list(m, "pending")) {
                @Suppress("UNCHECKED_CAST")
                (p as? MutableMap<String, Any?>)?.let { st.pending.add(it) }
            }
            for (s in AppJson.list(m, "seenOps")) {
                if (s is String) st.seenOps.add(s)
            }
            if (st.deviceId.isEmpty()) st.deviceId = Device.newId()
            if (st.deviceName.isEmpty()) st.deviceName = "android-" + st.deviceId.take(4)
            return st
        }
    }
}

class SyncResult {
    var ok = false
    var pushed = 0
    var pulled = 0
    var applied = 0
    var head: Long = 0
    var error = ""
    var changed = false
}

class HttpError(val code: Int, val body: String) : Exception("HTTP $code: $body")

object ServerApi {

    private const val TIMEOUT_MS = 20000

    /** 发一个请求。返回 (状态码, 响应体)。 */
    fun request(cfg: SyncConfig, path: String, method: String = "GET",
                body: String? = null, token: Boolean = true): Pair<Int, String> {
        val conn = URL(cfg.base() + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            if (token && cfg.token.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + cfg.token)
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /** 健康检查（不需要 token）。 */
    fun ping(cfg: SyncConfig): Map<String, Any?> {
        return try {
            val (code, text) = request(cfg, "/health", token = false)
            if (code != 200) return mapOf("ok" to false, "error" to "HTTP $code")
            val m = AppJson.parseObject(text)
            m["ok"] = true
            m
        } catch (e: Exception) {
            mapOf("ok" to false, "error" to describe(e))
        }
    }

    fun describe(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "连接超时（服务器没响应）"
        is java.net.ConnectException -> "连不上服务器（检查地址、端口、安全组）"
        is java.net.UnknownHostException -> "域名解析失败"
        is HttpError -> "服务器返回 ${e.code}"
        else -> "${e.javaClass.simpleName}: ${e.message ?: ""}"
    }

    // ------------------------------------------------------------------
    // 应用更新
    // ------------------------------------------------------------------

    /** 查最新版。返回 null 表示服务器上还没有 APK。 */
    fun latestApp(cfg: SyncConfig): Map<String, Any?>? {
        val (code, text) = request(cfg, "/app/latest", token = false)
        if (code == 404) return null
        if (code != 200) throw HttpError(code, text)
        return AppJson.parseObject(text)
    }

    /** 向服务器上报客户端异常信息（无需 token） */
    fun reportError(cfg: SyncConfig, errorData: Map<String, Any?>) {
        if (!cfg.configured) return
        try {
            val payload = AppJson.write(errorData.toMutableMap())
            request(cfg, "/app/report-error", "POST", payload, token = false)
        } catch (_: Exception) {
        }
    }

    /**
     * 下载文件到本地，支持断点续传。
     *
     * @param onProgress (已下载字节, 总字节)；总字节未知时为 -1
     * @return 下载完成后的文件
     */
    fun download(urlStr: String, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        target.parentFile?.mkdirs()
        var existing = if (target.exists()) target.length() else 0L

        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = 60000        // 下载慢一点也正常
            conn.useCaches = false
            if (existing > 0) {
                conn.setRequestProperty("Range", "bytes=$existing-")
            }
            val code = conn.responseCode
            if (code == 416) {
                return target                  // 已经下完了
            }
            if (code !in 200..299) {
                throw HttpError(code, "")
            }
            if (code == 200) {
                existing = 0L                  // 服务器不支持续传，从头来
            }
            val remaining = conn.contentLengthLong
            val total = if (remaining > 0) existing + remaining else -1L

            val append = existing > 0 && code == 206
            FileOutputStream(target, append).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = existing
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                    out.flush()
                }
            }
            return target
        } finally {
            conn.disconnect()
        }
    }
}

// --------------------------------------------------------------------------
// 同步一轮
// --------------------------------------------------------------------------

object Syncer {

    private const val MAX_PUSH_BATCH = 500

    /**
     * 完整同步一轮：先推本地攒下的改动，再拉远端的新改动。
     *
     * @param lib 会被就地修改（应用远端改动），并在 result.changed=true 时
     *            由调用方负责落盘
     */
    fun syncOnce(cfg: SyncConfig, state: SyncState, lib: Library): SyncResult {
        val r = SyncResult()
        if (!cfg.configured) {
            r.error = "还没配置服务器地址或 token"
            return r
        }

        try {
            // ---- 1) 同步：拉取并应用远端改动 ----
            val (code, text) = ServerApi.request(cfg, "/sync/pull?since=" + state.lastVersion)
            if (code == 401) {
                r.error = "token 不对（401）"
                state.lastError = r.error
                return r
            }
            if (code != 200) {
                r.error = "服务器返回 $code"
                state.lastError = r.error
                return r
            }
            val pullBody = AppJson.parseObject(text)
            val remoteOps = parseOps(pullBody)
            r.head = AppJson.num(pullBody, "head")

            val groupAliases = mutableMapOf<String, String>()
            val entryAliases = mutableMapOf<String, String>()
            val applied = OpApplier.applyAll(lib, remoteOps, state.seenOps, null, groupAliases, entryAliases)
            if (applied > 0) {
                r.changed = true
            }
            r.applied = applied
            r.pulled = remoteOps.size

            // ---- 2) 推送本地改动 ----
            val toPush = state.pending.toMutableList()

            // 增量/补偿扫描：如果本地有存活词条或分组未记录在已同步集合中，自动补偿构建 op 推送
            val pushedOpKeys = mutableSetOf<String>()
            for (p in toPush) {
                val iid = (p["item_id"] as? String)?.takeIf { it.isNotBlank() }
                    ?: ((p["payload"] as? Map<*, *>)?.get("id") as? String)
                if (!iid.isNullOrBlank()) {
                    pushedOpKeys.add(iid)
                }
            }

            for (g in lib.aliveGroups()) {
                val gid = Items.id(g)
                val opId = "${state.deviceId}-g-$gid"
                if (!state.seenOps.contains(opId) && !pushedOpKeys.contains(gid)) {
                    val m = (g as? Item) ?: g.toMutableMap()
                    val op = makeOp(state, "group.add", m)
                    op["id"] = opId
                    toPush.add(op)
                    pushedOpKeys.add(gid)
                }
            }

            for (e in lib.aliveEntries()) {
                val eid = Items.id(e)
                val opId = "${state.deviceId}-e-$eid"
                if (!state.seenOps.contains(opId) && !pushedOpKeys.contains(eid)) {
                    val m = (e as? Item) ?: e.toMutableMap()
                    val op = makeOp(state, "entry.add", m)
                    op["id"] = opId
                    toPush.add(op)
                    pushedOpKeys.add(eid)
                }
            }

            // 兜底去重：同一批次内若针对同一个条目有多次操作，仅保留最新一条，彻底杜绝主键唯一约束冲突
            val deduplicatedToPush = mutableListOf<Item>()
            val seenItemIdsInBatch = mutableSetOf<String>()
            for (idx in toPush.indices.reversed()) {
                val p = toPush[idx]
                val iid = (p["item_id"] as? String)?.takeIf { it.isNotBlank() }
                    ?: ((p["payload"] as? Map<*, *>)?.get("id") as? String)
                if (iid.isNullOrBlank() || seenItemIdsInBatch.add(iid)) {
                    deduplicatedToPush.add(0, p)
                }
            }

            if (deduplicatedToPush.isNotEmpty()) {
                var i = 0
                while (i < deduplicatedToPush.size) {
                    val batch = deduplicatedToPush.subList(i, minOf(i + MAX_PUSH_BATCH, deduplicatedToPush.size))
                    val payload = AppJson.write(mutableMapOf(
                        "device" to state.deviceId,
                        "ops" to batch.toMutableList(),
                    ))
                    val (pc, pt) = ServerApi.request(cfg, "/sync/push", "POST", payload)
                    if (pc != 200) {
                        r.error = "推送失败：HTTP $pc " + pt.take(120)
                        state.lastError = r.error
                        return r
                    }
                    val res = AppJson.parseObject(pt)
                    r.pushed += AppJson.num(res, "accepted").toInt()
                    val h = AppJson.num(res, "head")
                    if (h > 0) r.head = h
                    for (op in batch) {
                        (op["id"] as? String)?.let { state.seenOps.add(it) }
                    }
                    i += MAX_PUSH_BATCH
                }
                state.pending.clear()
            }

            state.lastVersion = maxOf(state.lastVersion, r.head)
            state.lastSyncAt = Clock.nowIso()
            state.lastError = ""
            r.ok = true
            return r
        } catch (e: Exception) {
            r.error = ServerApi.describe(e)
            state.lastError = r.error
            return r
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseOps(body: Map<String, Any?>): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        for (o in AppJson.list(body, "ops")) {
            (o as? MutableMap<String, Any?>)?.let { out.add(it) }
        }
        return out
    }

    /** 把一次本地改动记进日志 + 待推送队列。 */
    fun makeOp(state: SyncState, kind: String, item: Item): Item {
        state.lastSeq += 1
        val itemId = Items.id(item)
        val op = mutableMapOf<String, Any?>(
            "id" to (state.deviceId + "-" + state.lastSeq),
            "dev" to state.deviceId,
            "seq" to state.lastSeq,
            "at" to Clock.nowIso(),
            "kind" to kind,
            "item_id" to itemId,
        )
        // payload 里的 _syncKey 要重写成**这条操作自己的身份**。
        // 用 payload 里带过来的旧身份的话，会拿旧序号去比较，
        // 结果这次改动被判成"比现状更旧"而被丢弃 —— 桌面端就踩过这个坑。
        val copy = OpApplier.deepCopy(item)
        copy["_syncKey"] = mutableListOf(op["at"] as String, state.deviceId, state.lastSeq)
        op["payload"] = copy
        return op
    }

    /** 记一条改动：进本地日志（由调用方存）+ 进待推送队列。 */
    fun record(state: SyncState, kind: String, item: Item): Item {
        val op = makeOp(state, kind, item)
        state.pending.add(op)
        return op
    }

    fun statusText(state: SyncState, cfg: SyncConfig): String {
        if (!cfg.configured) return "同步：未配置"
        val bits = mutableListOf("同步：v" + state.lastVersion)
        if (state.pending.isNotEmpty()) bits.add("待推送 " + state.pending.size + " 条")
        if (state.lastSyncAt.isNotEmpty()) bits.add(state.lastSyncAt.take(19).replace('T', ' '))
        if (state.lastError.isNotEmpty()) bits.add("上次失败")
        return bits.joinToString("  ·  ")
    }
}
