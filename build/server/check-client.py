"""与同步服务器对接的配置与自检。

用法：
  python build/server/check-client.py                  # 读环境变量或用下面的默认值
  python build/server/check-client.py <url> <token>    # 直接给

为什么单独放一个自检：
  token 是高熵字符串，肉眼转录极易出错（已经被 0/O、l/1 坑过一次）。
  这里会打印 token 的**指纹**（前 6 位 + 长度 + 校验和），
  以后核对时看指纹即可，不用再比字符串。
"""

import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request

DEFAULT_URL = os.environ.get("WM_URL", "http://127.0.0.1:18080")
ENV_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "client.env")


def token_fingerprint(tok: str) -> str:
    h = hashlib.sha256(tok.encode()).hexdigest()[:8]
    return f"{tok[:4]}…{tok[-4:]}  len={len(tok)}  sha256:{h}"


def load_env_file() -> dict:
    if not os.path.exists(ENV_FILE):
        return {}
    out = {}
    with open(ENV_FILE, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def call(url, path, token=None, method="GET", body=None, timeout=15):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url + path, data=data, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if data:
        req.add_header("Content-Type", "application/json")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read()), (time.time() - t0) * 1000
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read() or b"{}")
        except Exception:
            payload = {}
        return e.code, payload, (time.time() - t0) * 1000
    except Exception as e:
        return None, {"error": f"{type(e).__name__}: {e}"}, (time.time() - t0) * 1000


def main():
    args = sys.argv[1:]
    env = load_env_file()
    url = (args[0] if len(args) > 0 else
           os.environ.get("WM_URL") or env.get("WM_URL") or DEFAULT_URL)
    token = (args[1] if len(args) > 1 else
             os.environ.get("WM_TOKEN") or env.get("WM_TOKEN") or "")

    print("=" * 66)
    print("  单词混记 · 客户端连接自检")
    print("=" * 66)
    print(f"  服务器 : {url}")
    if token:
        print(f"  token  : {token_fingerprint(token)}")
    else:
        print("  token  : (未提供)")
    print()

    ok = True

    # 1) 健康检查（不需要 token）
    st, body, ms = call(url, "/health")
    print(f"  1) /health            -> {st}  {ms:.0f} ms")
    if st == 200:
        print(f"     {json.dumps(body, ensure_ascii=False)}")
        print(f"     服务端已有 {body.get('ops')} 条改动，当前版本 {body.get('head')}")
    else:
        print(f"     {body}")
        print("     → 服务器不可达。检查：安全组是否放行、服务是否在跑、地址是否正确")
        ok = False

    if not token:
        print("\n  没有 token，跳过鉴权检查")
        return 1 if not ok else 0

    # 2) 鉴权
    st, body, ms = call(url, "/sync/pull?since=0", token=token)
    print(f"\n  2) 带 token 拉取      -> {st}  {ms:.0f} ms")
    if st == 200:
        print(f"     拿到 {body.get('count')} 条改动，head={body.get('head')}")
    elif st == 401:
        print("     → 401：token 不对。请重新核对（用指纹比对，别肉眼比字符串）")
        ok = False
    else:
        print(f"     {body}")
        ok = False

    # 3) 错 token 必须被拒（确认鉴权真的生效，而不是形同虚设）
    st_bad, _, _ = call(url, "/sync/pull?since=0", token="definitely-wrong")
    print(f"\n  3) 错 token 应被拒     -> {st_bad}  {'✓ 正确' if st_bad == 401 else '✗ 异常！鉴权没生效'}")
    if st_bad != 401:
        ok = False

    # 4) 把配置落盘，后续客户端直接用
    if ok and token:
        lines = [
            "# 单词混记客户端连接配置（不要提交到版本库）",
            f"WM_URL={url}",
            f"WM_TOKEN={token}",
        ]
        with open(ENV_FILE, "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
        try:
            os.chmod(ENV_FILE, 0o600)
        except OSError:
            pass
        print(f"\n  配置已写入 {ENV_FILE}（权限 600）")

    print()
    print("  结果:", "全部通过，可以开始接客户端" if ok else "有问题，见上")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
