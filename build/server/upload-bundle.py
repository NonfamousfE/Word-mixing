#!/usr/bin/env python3
"""远程推送 Web 与后端更新包到云服务器（免终端命令）。

用法：
  python build/server/upload-bundle.py
"""

import os
import sys
import io
import tarfile
import urllib.request
import urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SERVER_DIR = os.path.join(ROOT, "server")


def load_cfg():
    cfg = {}
    for p in (os.path.join(HERE, "client.env"),):
        if os.path.exists(p):
            with open(p, encoding="utf-8") as fh:
                for line in fh:
                    if "=" in line and not line.strip().startswith("#"):
                        k, v = line.split("=", 1)
                        k_clean = k.strip().lower()
                        if k_clean.startswith("wm_"):
                            k_clean = k_clean[3:]
                        cfg[k_clean] = v.strip()
    if "url" not in cfg:
        cfg["url"] = os.environ.get("WM_URL", "http://127.0.0.1:18080")
    if "token" not in cfg:
        cfg["token"] = os.environ.get("WM_TOKEN", "")
    return cfg


def pack_bundle() -> bytes:
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        for root, dirs, files in os.walk(SERVER_DIR):
            if "__pycache__" in root or "data" in root:
                continue
            for f in files:
                if f.endswith(".pyc") or f == "wordmix.db":
                    continue
                full_p = os.path.join(root, f)
                arc_name = os.path.relpath(full_p, SERVER_DIR).replace("\\", "/")
                tar.add(full_p, arcname=arc_name)
    return buf.getvalue()


def main():
    cfg = load_cfg()
    token = cfg.get("token") or cfg.get("wm_token")
    if not token:
        print("错误: 未配置同步 Token！请设置环境变量 WM_TOKEN 或在 build/server/client.env 中配置。")
        return 1
    url = (cfg.get("url") or cfg.get("wm_url") or "http://127.0.0.1:18080").rstrip("/") + "/app/update-bundle"

    print("正在打包 server 及 web static 文件...")
    data = pack_bundle()
    print(f"打包完成，大小: {len(data) / 1024:.2f} KB，正在推送到 {url}...")

    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/octet-stream",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read().decode("utf-8")
            print("推送成功 OK")
            print(body)
            return 0
    except urllib.error.HTTPError as e:
        print(f"推送失败 HTTP {e.code}: {e.read().decode('utf-8', errors='replace')}")
        return 1
    except Exception as e:
        print(f"网络异常: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
