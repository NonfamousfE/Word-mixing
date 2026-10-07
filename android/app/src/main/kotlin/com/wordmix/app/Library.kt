package com.wordmix.app

import java.util.UUID

/**
 * 词库模型与"应用操作"的逻辑。
 *
 * 这里必须和桌面端 app/wordmix/sync.py 的语义**完全一致**，否则两端
 * 各自算出不同的结果，同步就会错乱。关键规则：
 *
 *   1. 删除用**墓碑**（deleted 标记），不能直接抹掉
 *      —— 否则离线设备的旧数据会把已删的词"复活"
 *   2. 同一条两边都改过时，按 (时间, 设备, 序号) 取新
 *      —— 时间相同时用 (设备, 序号) 定胜负，保证两端算出相同结果
 *   3. `_syncKey` 必须**随对象一起保存**，它是上面那条比较的依据
 *
 * 之所以反复强调：桌面端在这两点上都踩过坑（墓碑被 normalize 丢掉、
 * _syncKey 用了旧序号导致删除被判为"更旧"而丢弃）。
 */

const val F_WORD = "f_word"
const val F_MEANING = "f_meaning"
const val F_NOTE = "f_note"

// --------------------------------------------------------------------------
// 时间
// --------------------------------------------------------------------------

object Clock {
    /**
     * 带时区的 ISO 时间，精确到微秒。
     *
     * 必须带时区：桌面端就因为返回"裸本地时间"（看起来像 UTC 其实是本地时间）
     * 导致同一条数据在一台机器上比另一台"早 8 小时"，一次删除因此失效。
     */
    fun nowIso(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getDefault()
        return fmt.format(java.util.Date())
    }

    /** 解析成可比较的毫秒时间戳；解析不了就当"很旧"。 */
    fun parseMillis(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        // 先试带时区的标准格式，再退几种常见写法
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
        )
        for (p in patterns) {
            try {
                val fmt = java.text.SimpleDateFormat(p, java.util.Locale.US)
                fmt.timeZone = java.util.TimeZone.getDefault()
                val d = fmt.parse(iso) ?: continue
                return d.time
            } catch (_: Exception) {
                // 换下一个格式
            }
        }
        return 0L
    }
}

// --------------------------------------------------------------------------
// 设备身份
// --------------------------------------------------------------------------

object Device {
    fun newId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
}

// --------------------------------------------------------------------------
// 词条 / 分组
// --------------------------------------------------------------------------

/** 一条词条（或分组）的统一表示：直接就是可序列化的 Map，避免来回转换出错。 */
typealias Item = MutableMap<String, Any?>

object Items {

    fun newGroup(name: String): LinkedHashMap<String, Any?> {
        val now = Clock.nowIso()
        return linkedMapOf(
            "id" to "g_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
            "name" to name,
            "note" to "",
            "createdAt" to now,
            "updatedAt" to now,
        )
    }

    fun newEntry(groupId: String, word: String, meaning: String): LinkedHashMap<String, Any?> {
        val now = Clock.nowIso()
        return linkedMapOf(
            "id" to "e_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
            "groupId" to groupId,
            "fields" to linkedMapOf<String, Any?>(
                F_WORD to word, F_MEANING to meaning, F_NOTE to ""
            ),
            "createdAt" to now,
            "updatedAt" to now,
        )
    }

    fun word(it: Map<String, Any?>): String {
        val f = AppJson.obj(it, "fields") ?: return ""
        return AppJson.str(f, F_WORD)
    }

    fun meaning(it: Map<String, Any?>): String {
        val f = AppJson.obj(it, "fields") ?: return ""
        return AppJson.str(f, F_MEANING)
    }

    fun isDeleted(it: Map<String, Any?>?): Boolean =
        it != null && AppJson.bool(it, "deleted")

    fun id(it: Map<String, Any?>?): String =
        if (it == null) "" else AppJson.str(it, "id")

    /**
     * 比较用的合成键：(时间, 设备, 序号)。
     *
     * 为什么要带设备+序号：两端可能给出**完全相同**的时间戳（时钟精度、
     * 或同一秒内的改动）。只比时间会退化成"谁先读到谁赢"，
     * 不同设备重放顺序不同就会算出不同结果。
     */
    fun winnerKey(stamp: String?, op: Map<String, Any?>?): Triple<Long, String, Long> {
        val t = Clock.parseMillis(stamp)
        val dev = if (op == null) "" else AppJson.str(op, "dev")
        val seq = if (op == null) 0L else AppJson.num(op, "seq")
        return Triple(t, dev, seq)
    }

    /**
     * 记下"这个对象是被哪次操作赢下的"，供以后比较。
     *
     * ⚠ 第一个元素必须是**那条操作自己的时间戳**，不能用当前时间。
     *   一开始我写成 `Clock.nowIso()`（即写入时刻），结果是：
     *   本地已有对象的时间总是"现在"，永远比远端来的旧操作"新"，
     *   于是所有同步过来的改动都被判为更旧而丢弃 ——
     *   表现就是"删除同步不过来""手机改了电脑看不到"。
     *   桌面端存的就是被选中操作的时间，这里必须一致。
     */
    fun saveSyncKey(item: Item, stamp: String, dev: String, seq: Long) {
        // 存成 [时间串, 设备, 序号] 三元素数组，和桌面端格式一致
        item["_syncKey"] = mutableListOf(
            stamp.ifEmpty { Clock.nowIso() }, dev, seq
        )
    }

    fun syncKeyOf(item: Item): Triple<Long, String, Long>? {
        val raw = item["_syncKey"] as? List<*> ?: return null
        if (raw.size != 3) return null
        val stamp = raw[0] as? String ?: return null
        val dev = raw[1] as? String ?: ""
        val seq = (raw[2] as? Number)?.toLong() ?: 0L
        return Triple(Clock.parseMillis(stamp), dev, seq)
    }
}

// --------------------------------------------------------------------------
// 词库
// --------------------------------------------------------------------------

class Library {
    val groups = mutableListOf<Item>()
    val entries = mutableListOf<Item>()

    // 注意这些方法显式写成 LinkedHashMap<String, Any?> 而不是 Item：
    // Item 是 typealias，Java 看不到（单元测试是 Java 写的，见
    // SyncProtocolTest 的说明），写成具体类型 Java 才能直接调用。
    fun findGroup(id: String): LinkedHashMap<String, Any?>? =
        groups.firstOrNull { Items.id(it) == id } as? LinkedHashMap<String, Any?>

    fun findEntry(id: String): LinkedHashMap<String, Any?>? =
        entries.firstOrNull { Items.id(it) == id } as? LinkedHashMap<String, Any?>

    /** 界面上要显示的词条（排除墓碑）。 */
    fun aliveEntries(): List<Map<String, Any?>> = entries.filter { !Items.isDeleted(it) }

    fun aliveGroups(): List<Map<String, Any?>> = groups.filter { !Items.isDeleted(it) }

    fun entriesOf(groupId: String): List<Map<String, Any?>> =
        aliveEntries().filter { AppJson.str(it, "groupId") == groupId }

    fun toMap(): MutableMap<String, Any?> = mutableMapOf(
        "schemaVersion" to 1L,
        "revision" to revision,
        "updatedAt" to Clock.nowIso(),
        "groups" to groups.toMutableList(),
        "entries" to entries.toMutableList(),
    )

    var revision: Long = 0

    companion object {
        fun fromMap(m: Map<String, Any?>?): Library {
            val lib = Library()
            lib.revision = AppJson.num(m, "revision")
            for (g in AppJson.list(m, "groups")) {
                @Suppress("UNCHECKED_CAST")
                (g as? MutableMap<String, Any?>)?.let { lib.groups.add(it) }
            }
            for (e in AppJson.list(m, "entries")) {
                @Suppress("UNCHECKED_CAST")
                (e as? MutableMap<String, Any?>)?.let { lib.entries.add(it) }
            }
            return lib
        }

        fun fromJson(text: String): Library = fromMap(AppJson.parseObject(text))
    }

    fun toJson(): String = AppJson.writePretty(toMap())
}

// --------------------------------------------------------------------------
// 应用一条操作（和桌面端 sync.py 的 _upsert 等价）
// --------------------------------------------------------------------------

object OpApplier {

    /**
     * 把一条操作应用到词库。返回 "add" / "update" / "skip"。
     *
     * @param isGroup 该操作针对分组还是词条
     * @param tomb    是否是删除（墓碑）操作
     */
    fun cleanWordKey(word: String?): String =
        (word ?: "").replace('’', '\'').replace('‘', '\'').lowercase().replace(Regex("\\s+"), " ").trim()

    fun cleanGroupKey(name: String?): String =
        (name ?: "").lowercase().replace(Regex("\\s+"), " ").trim()

    fun findSameGroup(bucket: List<Item>, payload: Item): Item? {
        val name = cleanGroupKey(AppJson.str(payload, "name"))
        if (name.isEmpty()) return null
        return bucket.firstOrNull {
            !Items.isDeleted(it) && cleanGroupKey(AppJson.str(it, "name")) == name
        }
    }

    fun findSameWord(bucket: List<Item>, payload: Item): Item? {
        val word = cleanWordKey(Items.word(payload))
        if (word.isEmpty()) return null
        val gid = AppJson.str(payload, "groupId")
        return bucket.firstOrNull {
            !Items.isDeleted(it) &&
            (gid.isEmpty() || AppJson.str(it, "groupId") == gid) &&
            cleanWordKey(Items.word(it)) == word
        }
    }

    @JvmOverloads
    fun upsert(lib: Library, isGroup: Boolean, payload: Item, tomb: Boolean,
               op: Map<String, Any?>?, groupAliases: MutableMap<String, String>? = null,
               entryAliases: MutableMap<String, String>? = null): String {
        val bucket = if (isGroup) lib.groups else lib.entries
        val itemId = AppJson.str(payload, "id")
        if (itemId.isEmpty()) return "skip"

        if (!isGroup && groupAliases != null) {
            val gid = AppJson.str(payload, "groupId")
            val targetGid = groupAliases[gid]
            if (targetGid != null) {
                payload["groupId"] = targetGid
            }
        }

        var existing = bucket.firstOrNull { Items.id(it) == itemId }
        var isDupMerge = false

        // 内容级去重：同名分组归并、同组同词归并
        if (existing == null && !tomb) {
            if (isGroup) {
                val dup = findSameGroup(bucket, payload)
                if (dup != null) {
                    existing = dup
                    isDupMerge = true
                }
            } else {
                val dup = findSameWord(bucket, payload)
                if (dup != null) {
                    existing = dup
                    isDupMerge = true
                }
            }
        }

        // 墓碑的时间以 deletedAt 为准，这样"删除"和"修改"能直接比大小
        val stamp = (if (tomb) AppJson.str(payload, "deletedAt") else "")
            .ifEmpty { AppJson.str(payload, "updatedAt") }
            .ifEmpty { if (op == null) "" else AppJson.str(op, "at") }
        val incoming = Items.winnerKey(stamp, op)

        if (existing == null) {
            val copy = deepCopy(payload)
            if (tomb) {
                copy["deleted"] = true
                copy["deletedAt"] = AppJson.str(payload, "deletedAt").ifEmpty { stamp }
            } else {
                copy.remove("deleted")
                copy.remove("deletedAt")
            }
            Items.saveSyncKey(copy, stamp, incoming.second, incoming.third)
            bucket.add(copy)
            return "add"
        }

        if (isDupMerge) {
            val origId = Items.id(existing)
            val remoteId = itemId
            existing["id"] = remoteId
            if (isGroup) {
                groupAliases?.put(origId, remoteId)
                for (e in lib.entries) {
                    if (AppJson.str(e, "groupId") == origId) {
                        e["groupId"] = remoteId
                    }
                }
            } else {
                entryAliases?.put(origId, remoteId)
                val locM = Items.meaning(existing).trim()
                val incM = Items.meaning(payload).trim()
                val merged = when {
                    incM.isNotEmpty() && locM.isNotEmpty() && !locM.contains(incM) && !incM.contains(locM) -> "$incM；$locM"
                    incM.isNotEmpty() && locM.isEmpty() -> incM
                    else -> locM
                }
                @Suppress("UNCHECKED_CAST")
                val fields = existing["fields"] as? MutableMap<String, Any?> ?: mutableMapOf()
                fields["f_meaning"] = merged
                existing["fields"] = fields
            }
            val u = AppJson.str(payload, "updatedAt").ifEmpty { stamp }
            val curU = AppJson.str(existing, "updatedAt")
            existing["updatedAt"] = if (u > curU) u else curU
            Items.saveSyncKey(existing, stamp, incoming.second, incoming.third)
            return "update"
        }

        val currentStamp = (if (Items.isDeleted(existing)) AppJson.str(existing, "deletedAt") else "")
            .ifEmpty { AppJson.str(existing, "updatedAt") }
        val savedRaw = existing["_syncKey"] as? List<*>
        val savedStamp = if (savedRaw != null && savedRaw.isNotEmpty()) savedRaw[0] as? String ?: "" else ""
        val localKey = (if (savedStamp.isNotEmpty() && (currentStamp.isEmpty() || savedStamp == currentStamp)) {
            Items.syncKeyOf(existing)
        } else null) ?: Items.winnerKey(currentStamp, null)

        if (compare(incoming, localKey) <= 0) {
            if (!isGroup && !tomb) {
                val locM = Items.meaning(existing)
                val incM = Items.meaning(payload)
                if (locM.isEmpty() && incM.isNotEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    val fields = existing["fields"] as? MutableMap<String, Any?>
                    if (fields != null) {
                        fields["f_meaning"] = incM
                        return "update"
                    }
                }
            }
            return "skip"
        }

        if (tomb) {
            existing["deleted"] = true
            existing["deletedAt"] = AppJson.str(payload, "deletedAt").ifEmpty { stamp }
            existing["updatedAt"] = existing["deletedAt"]
        } else {
            existing.remove("deleted")
            existing.remove("deletedAt")
            val origId = Items.id(existing)
            for ((k, v) in payload) {
                if (k != "kind" && k != "dev" && k != "seq" && k != "payload" && k != "id") {
                    existing[k] = deepCopyValue(v)
                }
            }
            if (origId.isNotEmpty()) {
                existing["id"] = origId
            }
            val u = AppJson.str(payload, "updatedAt")
            if (u.isNotEmpty()) existing["updatedAt"] = u
        }
        Items.saveSyncKey(existing, stamp, incoming.second, incoming.third)
        return "update"
    }

    /**
     * 三元素键比较。时间相同时比设备号，再比序号。
     */
    fun compare(a: Triple<Long, String, Long>, b: Triple<Long, String, Long>): Int {
        if (a.first != b.first) return if (a.first < b.first) -1 else 1
        val c = a.second.compareTo(b.second)
        if (c != 0) return if (c < 0) -1 else 1
        return when {
            a.third < b.third -> -1
            a.third > b.third -> 1
            else -> 0
        }
    }

    @JvmOverloads
    fun apply(lib: Library, op: Map<String, Any?>, groupAliases: MutableMap<String, String>? = null,
              entryAliases: MutableMap<String, String>? = null): Boolean {
        val kind = AppJson.str(op, "kind")
        val payload = AppJson.obj(op, "payload") ?: return false
        return when (kind) {
            "group.add", "group.update" -> upsert(lib, true, payload, false, op, groupAliases, entryAliases) != "skip"
            "group.remove" -> upsert(lib, true, payload, true, op, groupAliases, entryAliases) != "skip"
            "entry.add", "entry.update" -> upsert(lib, false, payload, false, op, groupAliases, entryAliases) != "skip"
            "entry.remove" -> upsert(lib, false, payload, true, op, groupAliases, entryAliases) != "skip"
            else -> false
        }
    }

    /** 按确定顺序重放一批操作；已应用过的（seenOps）跳过。 */
    @JvmOverloads
    fun applyAll(lib: Library, ops: List<Map<String, Any?>>,
                 seen: MutableSet<String>, seenCount: LongArray? = null,
                 groupAliases: MutableMap<String, String>? = null,
                 entryAliases: MutableMap<String, String>? = null): Int {
        var applied = 0
        val pending = ops.filter { op ->
            val id = AppJson.str(op, "id")
            id.isNotEmpty() && !seen.contains(id)
        }
        val sorted = pending.sortedWith(compareBy({ Clock.parseMillis(AppJson.str(it, "at")) },
            { AppJson.str(it, "dev") }, { AppJson.num(it, "seq") }))
        val gAliases = groupAliases ?: mutableMapOf()
        val eAliases = entryAliases ?: mutableMapOf()
        for (op in sorted) {
            if (apply(lib, op, gAliases, eAliases)) applied++
            seen.add(AppJson.str(op, "id"))
            if (seenCount != null) seenCount[0] = seenCount[0] + 1
        }
        return applied
    }

    /**
     * 根据当前词库清洗待推送队列，防止重复推送已存在的数据导致 a+c+c
     */
    @JvmOverloads
    fun reconcilePending(lib: Library, pending: List<Item>,
                         groupAliases: MutableMap<String, String>? = null,
                         entryAliases: MutableMap<String, String>? = null): List<Item> {
        if (pending.isEmpty()) return emptyList()

        val gAliases = if (groupAliases != null) mutableMapOf<String, String>().apply { putAll(groupAliases) } else mutableMapOf()
        val eAliases = if (entryAliases != null) mutableMapOf<String, String>().apply { putAll(entryAliases) } else mutableMapOf()

        val groupByNorm = mutableMapOf<String, String>()
        for (g in lib.aliveGroups()) {
            val n = cleanGroupKey(AppJson.str(g, "name"))
            if (n.isNotEmpty()) groupByNorm[n] = Items.id(g)
        }

        val entryByGroupWord = mutableMapOf<Pair<String, String>, Map<String, Any?>>()
        for (e in lib.aliveEntries()) {
            val gid = AppJson.str(e, "groupId")
            val w = cleanWordKey(Items.word(e))
            if (gid.isNotEmpty() && w.isNotEmpty()) {
                entryByGroupWord[Pair(gid, w)] = e
            }
        }

        val cleaned = mutableListOf<Item>()

        for (op in pending) {
            val kind = AppJson.str(op, "kind")
            val payload = AppJson.obj(op, "payload")
            if (payload == null) {
                cleaned.add(op)
                continue
            }

            if (kind == "group.add") {
                val norm = cleanGroupKey(AppJson.str(payload, "name"))
                val pid = Items.id(payload)
                val existGid = groupByNorm[norm]
                if (existGid != null && (existGid != pid || gAliases.containsKey(pid))) {
                    val targetGid = gAliases[pid] ?: existGid
                    gAliases[pid] = targetGid
                    continue
                }
                cleaned.add(op)
                continue
            }

            if (kind == "entry.add") {
                var gid = AppJson.str(payload, "groupId")
                val aliased = gAliases[gid]
                if (aliased != null) {
                    gid = aliased
                    payload["groupId"] = gid
                }
                val w = cleanWordKey(Items.word(payload))
                val pid = Items.id(payload)
                val real = entryByGroupWord[Pair(gid, w)]
                if (real != null && (Items.id(real) != pid || eAliases.containsKey(pid))) {
                    val targetId = eAliases[pid] ?: Items.id(real)
                    val pMeaning = Items.meaning(payload).trim()
                    val rMeaning = Items.meaning(real).trim()
                    if (pMeaning.isEmpty() || pMeaning == rMeaning || rMeaning.contains(pMeaning)) {
                        continue
                    } else {
                        val opCopy = deepCopy(op)
                        val pCopy = deepCopy(payload)
                        pCopy["id"] = targetId
                        opCopy["kind"] = "entry.update"
                        opCopy["payload"] = pCopy
                        cleaned.add(opCopy)
                        continue
                    }
                }
            }

            if (kind == "entry.update" || kind == "entry.remove") {
                val gid = AppJson.str(payload, "groupId")
                val aliased = gAliases[gid]
                if (aliased != null) {
                    payload["groupId"] = aliased
                }
                val pid = Items.id(payload)
                val targetId = eAliases[pid]
                if (targetId != null) {
                    payload["id"] = targetId
                }
            }

            cleaned.add(op)
        }
        return cleaned
    }

    // ------------------------------------------------------------------
    // 深拷贝（避免两个设备共享同一个 Map 引用导致串改）
    // ------------------------------------------------------------------

    fun deepCopy(src: Item): LinkedHashMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return deepCopyValue(src) as LinkedHashMap<String, Any?>
    }

    fun deepCopyValue(v: Any?): Any? = when (v) {
        is Map<*, *> -> {
            val m = LinkedHashMap<String, Any?>()
            for ((k, value) in v) m[k.toString()] = deepCopyValue(value)
            m
        }
        is List<*> -> {
            val l = ArrayList<Any?>()
            for (item in v) l.add(deepCopyValue(item))
            l
        }
        else -> v
    }
}
