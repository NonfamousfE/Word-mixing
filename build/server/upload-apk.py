#!/usr/bin/env python3
"""把 APK 上传到同步服务器，供安卓端自更新下载。

用法：
  python build/server/upload-apk.py <APK路径> <versionCode> <versionName> ["更新说明"]
  python build/server/upload-apk.py --show                # 看服务器上当前是哪个版本

例：
  python build/server/upload-apk.py dist/wordmix-1.0.1.apk 2 1.0.1 "修了释义遮挡的显示"
"""

import json
import os
import sys
import urllib.error
import urllib.request

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(ROOT, "app"))
sys.path.insert(0, HERE)


def load_cfg():
    try:
        from wordmix import syncer

        return syncer.load_config()
    except Exception:                                   # noqa: BLE001
        cfg = {}
        for p in (os.path.join(HERE, "client.env"),):
            if os.path.exists(p):
                with open(p, encoding="utf-8") as fh:
                    for line in fh:
                        if "=" in line and not line.strip().startswith("#"):
                            k, v = line.split("=", 1)
                            k_clean = k.strip().upper()
                            if k_clean == "WM_URL":
                                cfg["url"] = v.strip()
                            elif k_clean == "WM_TOKEN":
                                cfg["token"] = v.strip()
        if not cfg.get("url"):
            cfg["url"] = os.environ.get("WM_URL", "")
        if not cfg.get("token"):
            cfg["token"] = os.environ.get("WM_TOKEN", "")
        return cfg


def show(cfg):
    try:
        with urllib.request.urlopen(cfg["url"] + "/app/latest", timeout=15) as r:
            d = json.loads(r.read())
        print("服务器上当前的 APK：")
        print(f"  版本      : {d['versionName']}  (versionCode {d['versionCode']})")
        print(f"  大小      : {d['size'] / 1024 / 1024:.2f} MB")
        print(f"  上传时间  : {d.get('uploadedAt', '')}")
        print(f"  sha256    : {d.get('sha256', '')[:16]}…")
        print(f"  下载地址  : {d['url']}")
        if d.get("changelog"):
            print(f"  更新说明  : {d['changelog']}")
        return 0
    except urllib.error.HTTPError as e:
        if e.code == 404:
            print("服务器上还没有上传过 APK。")
            print("上传命令：python build/server/upload-apk.py <APK路径> <versionCode> <versionName> \"说明\"")
        elif e.code == 401:
            # 旧版服务端没有 /app/latest 这个接口，请求会落到需要鉴权的分支上。
            # 这个提示要能自己解释清楚，否则很容易被误当成 token 配错了。
            print("查询失败：HTTP 401")
            print()
            print("  ⚠ 这几乎可以肯定不是 token 的问题，而是**服务器上跑的还是旧版后台**。")
            print("    旧版没有 /app/latest 这个接口，未匹配的请求会落到需要鉴权的分支，")
            print("    于是返回 401。")
            print()
            print("  解决办法：重新部署一次后台（详见 docs/使用与升级教程.md §四）")
            print("    python build/server/make-deploy.py")
            print("    然后把 build/dist-deploy/deploy.sh 全文粘到阿里云网页终端执行")
            print()
            print("  注：不重新部署也不影响日常同步，只是暂时用不了自动更新。")
        else:
            print(f"查询失败：HTTP {e.code}")
        return 1
    except Exception as e:                              # noqa: BLE001
        print(f"连不上服务器：{type(e).__name__}: {e}")
        print("（检查网络、服务器是否在跑：浏览器打开 " + cfg["url"] + "/health）")
        return 1


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    cfg = load_cfg()
    if not cfg.get("url") or not cfg.get("token"):
        print("没有配置服务器地址或 token。先跑 build/server/check-client.py")
        return 1

    if sys.argv[1] == "--show":
        return show(cfg)

    if len(sys.argv) < 4:
        print(__doc__)
        return 1
    apk, vcode, vname = sys.argv[1], sys.argv[2], sys.argv[3]
    changelog = sys.argv[4] if len(sys.argv) > 4 else ""

    if not os.path.exists(apk):
        print(f"找不到文件：{apk}")
        return 1
    size = os.path.getsize(apk)
    with open(apk, "rb") as fh:
        head = fh.read(2)
    if head != b"PK":
        print(f"这个文件不像 APK（开头是 {head!r}，应该是 b'PK'）")
        return 1

    print(f"上传 {apk}")
    print(f"  大小      : {size / 1024 / 1024:.2f} MB")
    print(f"  版本      : {vname} (versionCode {vcode})")
    if changelog:
        print(f"  更新说明  : {changelog}")

    # 中文放请求头要先 percent-encode
    from urllib.parse import quote

    req = urllib.request.Request(cfg["url"] + "/app/upload", method="POST")
    req.add_header("Authorization", "Bearer " + cfg["token"])
    req.add_header("Content-Type", "application/vnd.android.package-archive")
    req.add_header("X-Version-Code", str(vcode))
    req.add_header("X-Version-Name", vname)
    req.add_header("X-Changelog", quote(changelog, safe=""))
    with open(apk, "rb") as fh:
        body = fh.read()
    req.data = body

    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            res = json.loads(r.read())
    except urllib.error.HTTPError as e:
        print("上传失败：", e.code, e.read().decode("utf-8", "replace")[:300])
        return 1
    except Exception as e:                              # noqa: BLE001
        print(f"上传失败：{type(e).__name__}: {e}")
        return 1

    print("\n上传成功 ✓")
    print(f"  sha256 : {res.get('sha256', '')[:24]}…")
    print(f"  下载地址: {cfg['url']}/app/download/{res.get('file', '')}")
    print("\n安卓端下次启动就会看到这个新版。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
