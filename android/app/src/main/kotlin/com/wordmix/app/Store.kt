package com.wordmix.app

import java.io.File

/**
 * 本地存储：词库 + 同步状态。
 *
 * 只依赖 java.io.File，不碰 Android Context —— 这样单元测试里
 * 指向任意临时目录就能跑，不必装到手机上。
 * 目录由调用方给出（安卓上是 context.filesDir，测试里是临时目录）。
 */
class Store(val dir: File) {

    private val libFile = File(dir, "library.json")
    private val stateFile = File(dir, "sync-state.json")
    private val confFile = File(dir, "sync.conf")

    init {
        dir.mkdirs()
    }

    // ------------------------------------------------------------------
    // 词库
    // ------------------------------------------------------------------

    fun loadLibrary(): Library {
        if (!libFile.exists()) return Library()
        return try {
            Library.fromJson(libFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            // 词库文件坏了不能让 App 起不来：备份一份再看能不能救
            try {
                libFile.copyTo(File(dir, "library.broken.json"), overwrite = true)
            } catch (_: Exception) {
            }
            Library()
        }
    }

    fun saveLibrary(lib: Library) {
        atomicWrite(libFile, lib.toJson())
    }

    // ------------------------------------------------------------------
    // 同步状态
    // ------------------------------------------------------------------

    fun loadState(): SyncState {
        if (!stateFile.exists()) return SyncState().also { it.deviceId = Device.newId() }
        return try {
            SyncState.fromJson(stateFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            SyncState().also { it.deviceId = Device.newId() }
        }
    }

    fun saveState(st: SyncState) {
        atomicWrite(stateFile, st.toJson())
    }

    // ------------------------------------------------------------------
    // 服务器配置
    // ------------------------------------------------------------------

    fun loadConfig(): SyncConfig {
        val cfg = SyncConfig()
        if (confFile.exists()) {
            for (line in confFile.readLines()) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue
                val k = t.substringBefore("=").trim()
                val v = t.substringAfter("=").trim()
                when (k) {
                    "WM_URL" -> cfg.url = v
                    "WM_TOKEN" -> cfg.token = v
                }
            }
        }
        return cfg
    }

    fun saveConfig(cfg: SyncConfig) {
        atomicWrite(confFile, "WM_URL=" + cfg.url + "\nWM_TOKEN=" + cfg.token + "\n")
    }

    // ------------------------------------------------------------------
    // 原子写：先写临时文件再替换
    //
    // 手机上进程随时可能被杀（切后台、内存不足），直接覆盖写有概率
    // 留下半个文件，那比丢一次改动更糟 —— 整个词库都读不出来。
    // ------------------------------------------------------------------

    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            // renameTo 在某些情况下会失败（目标被占用），退化成复制
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
