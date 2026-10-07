package com.wordmix.app

import java.io.File
import java.security.MessageDigest

/**
 * 应用自更新：查版本 → 下载 → 校验 → 交给系统安装。
 *
 * 为什么不用应用商店：自用场景，塞进商店要走审核、还得公开。
 * 服务器已经在跑，让它顺便当更新源最省事。
 *
 * 用户能感知到的成本：只有一个"是否安装"的系统确认框。
 * 安卓从 8.0 起**不允许应用静默安装**（这是系统的安全底线，
 * 任何 App 都绕不过），所以"点一下确认"是能做到的最低限度。
 */

class UpdateInfo {
    var versionCode: Long = 0
    var versionName: String = ""
    var size: Long = 0
    var sha256: String = ""
    var changelog: String = ""
    var uploadedAt: String = ""
    var url: String = ""
}

object Updater {

    /** 查服务器上的最新版；返回 null 表示"查不到有新版本"。 */
    fun check(cfg: SyncConfig): UpdateInfo? {
        val m = try {
            ServerApi.latestApp(cfg) ?: return null
        } catch (e: HttpError) {
            // 服务器还没有更新接口（旧版本部署）时，/app/latest 会落到
            // 受鉴权的分支而返回 401，或直接 404。
            // 这**不是错误** —— 只是"没有更新可装"，不该让用户看到报错。
            // （服务端部署新版后就没有这个问题了。）
            if (e.code == 401 || e.code == 404 || e.code == 405) return null
            throw e
        }
        val info = UpdateInfo()
        info.versionCode = AppJson.num(m, "versionCode")
        info.versionName = AppJson.str(m, "versionName")
        info.size = AppJson.num(m, "size")
        info.sha256 = AppJson.str(m, "sha256")
        info.changelog = AppJson.str(m, "changelog")
        info.uploadedAt = AppJson.str(m, "uploadedAt")
        var u = AppJson.str(m, "url")
        if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) {
            u = cfg.base() + (if (u.startsWith("/")) u else "/$u")
        }
        info.url = u
        return info
    }

    fun needsUpdate(info: UpdateInfo?, currentVersionCode: Long): Boolean =
        info != null && info.versionCode > currentVersionCode && info.url.isNotEmpty()

    /**
     * 下载 APK。已存在且大小一致就直接复用（避免重复下载）。
     *
     * @param onProgress (已下载, 总计)，总计未知为 -1
     */
    fun download(info: UpdateInfo, targetDir: File,
                 onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        targetDir.mkdirs()
        val target = File(targetDir, "wordmix-" + info.versionCode + ".apk")

        // 已经有一个大小对得上的，直接使用（续传后也走这条路）
        if (target.exists() && info.size > 0 && target.length() == info.size) {
            if (info.sha256.isEmpty() || sha256Of(target) == info.sha256) {
                return target
            }
            target.delete()          // 校验不过就重下
        }

        ServerApi.download(info.url, target, onProgress)

        // 校验：下载可能被截断或被中间设备篡改
        if (info.sha256.isNotEmpty()) {
            val got = sha256Of(target)
            if (got != info.sha256) {
                target.delete()
                throw IllegalStateException("下载校验失败（sha256 不符），请重试")
            }
        }
        return target
    }

    fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { String.format("%02x", it) }
    }

    /** 旧版本残留的安装包清掉，别一直占空间。 */
    fun cleanOld(targetDir: File, keepVersionCode: Long) {
        val files = targetDir.listFiles { f -> f.name.endsWith(".apk") } ?: return
        for (f in files) {
            if (!f.name.contains("-" + keepVersionCode + ".apk")) {
                f.delete()
            }
        }
    }
}
