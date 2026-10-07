"""用 GitHub REST API 把当前提交推到远端。

为什么需要这个脚本：本机沙箱里 **`git push` 用不了**。
  git 的 HTTPS 传输层要起 `sh.exe`，而沙箱禁止创建命名管道，
  `sh.exe` 直接崩（`couldn't create signal pipe, Win32 error 5`），
  连 `git ls-remote`（无需认证）都失败。
  本地命令（init / add / commit / config）都正常，只有网络传输不行。

做法：读本地提交的内容 → 逐个建 blob → 建 tree → 建 commit → 更新 ref，
等价于一次 git push，只是提交发生在服务端。

用法：
  python build/push-github.py            # 推送到远端当前分支
  python build/push-github.py --dry-run  # 只建 blob，不产生提交
"""

import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
API = "https://api.github.com"
GH = r"C:\Program Files\GitHub CLI\gh.exe"

# 按二进制处理；其余按文本（换行统一成 LF，与 .gitattributes 一致）
BINARY_EXT = {".gz", ".png", ".ico", ".xlsx", ".exe", ".zip", ".pyd", ".dll", ".apk", ".jks"}


def git(*args: str) -> str:
    r = subprocess.run(["git"] + list(args), cwd=ROOT, capture_output=True, timeout=60)
    return r.stdout.decode("utf-8", "replace")


# 自动加载 git 配置中的代理
_proxy = git("config", "--get", "http.proxy").strip()
if _proxy:
    os.environ.setdefault("HTTP_PROXY", _proxy)
    os.environ.setdefault("HTTPS_PROXY", _proxy)
    os.environ.setdefault("http_proxy", _proxy)
    os.environ.setdefault("https_proxy", _proxy)


def remote_repo() -> tuple[str, str]:
    """从 origin 读出 owner/repo 和当前分支。"""
    url = git("remote", "get-url", "origin").strip()
    m = re.search(r"github\.com[:/](?P<owner>[^/]+)/(?P<repo>[^/]+?)(?:\.git)?$", url)
    if not m:
        raise SystemExit(f"无法从 origin 解析出仓库：{url!r}\n请先 git remote add origin ...")
    branch = git("rev-parse", "--abbrev-ref", "HEAD").strip() or "main"
    if branch == "HEAD":
        branch = "main"
    return f"{m.group('owner')}/{m.group('repo')}", branch


def get_token() -> str:
    """向 gh 要一个可用于 API 的 token（不读明文，不落盘）。"""
    inp = "protocol=https\nhost=github.com\n\n"
    r = subprocess.run([GH, "auth", "git-credential", "get"], input=inp,
                       capture_output=True, text=True, timeout=30)
    for line in (r.stdout or "").splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("拿不到 token，请先 `gh auth login`：\n" + (r.stderr or r.stdout or ""))


def api(token: str, method: str, path: str, body=None):
    url = path if path.startswith("http") else API + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    for attempt in range(3):
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", f"Bearer {token}")
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        req.add_header("User-Agent", "wordmix-push")
        if data:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            return e.code, {"error": e.read().decode("utf-8", "replace")}
        except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
            if attempt < 2:
                time.sleep(1.5)
                continue
            raise


def files_to_push() -> list[str]:
    """要推送的文件清单：以 **HEAD 的树**为准，只保留工作区里真实存在的。

    为什么不看暂存区（曾经有个 bug 就出在这）：
      `git diff --cached --name-only` 会把**删除**也列进来
      （比如 `git rm --cached tmpmsg.txt`）。那个文件在工作区已经没了，
      于是脚本去读它 → FileNotFoundError 直接崩掉。
      以 HEAD 的树为清单、再按"文件是否存在"过滤，
      删除的文件自然就不在快照里了 —— 因为每次推送都是完整快照，
      少一个文件就等于把它从远端删掉。
    """
    out = git("ls-tree", "-r", "--name-only", "-z", "HEAD")
    files = [p for p in out.split("\0") if p]

    kept, missing = [], []
    for rel in files:
        if os.path.isfile(os.path.join(ROOT, rel)):
            kept.append(rel)
        else:
            missing.append(rel)
    if missing:
        # 不是错误：HEAD 里有、工作区里已经删掉的文件，
        # 不放进新快照就等于从远端移除。
        print(f"  跳过 {len(missing)} 个已删除的文件（将从远端一并移除）：")
        for rel in missing[:5]:
            print(f"    - {rel}")
        if len(missing) > 5:
            print(f"    … 还有 {len(missing) - 5} 个")
    return kept


def read_for_git(rel: str) -> bytes:
    with open(os.path.join(ROOT, rel), "rb") as fh:
        raw = fh.read()
    if os.path.splitext(rel)[1].lower() in BINARY_EXT or b"\0" in raw[:8000]:
        return raw
    return raw.replace(b"\r\n", b"\n")


def bootstrap_if_empty(token: str, repo: str, branch: str) -> str | None:
    """空仓库要先有至少一次提交，Git Data API 才肯建 blob（否则 409）。"""
    st, ref = api(token, "GET", f"/repos/{repo}/git/ref/heads/{branch}")
    if st == 200 and ref:
        return None

    st, res = api(token, "PUT", f"/repos/{repo}/contents/.gitkeep",
                  {"message": "chore: bootstrap repository", "content": "", "branch": "init"})
    if st in (200, 201):
        sha = res["commit"]["sha"]
        print(f"  已在 init 分支完成引导提交 {sha[:8]}（正式提交里不含该占位文件）")
        return sha
    st2, ref2 = api(token, "GET", f"/repos/{repo}/git/ref/heads/init")
    if st2 == 200 and ref2:
        return ref2["object"]["sha"]
    print(f"  引导失败 {st}: {res}")
    return None


def main():
    dry = "--dry-run" in sys.argv
    repo, branch = remote_repo()
    print(f"仓库 {repo}  分支 {branch}")

    token = get_token()
    files = files_to_push()
    print(f"待推送 {len(files)} 个文件")
    if not files:
        print("没有可推送的内容（先 git add）")
        return 1

    st, info = api(token, "GET", f"/repos/{repo}")
    if st != 200:
        print(f"读仓库失败 {st}: {info}")
        return 1
    print(f"  可见性={info['visibility']}  默认分支={info.get('default_branch')}")

    parent = bootstrap_if_empty(token, repo, branch)
    if parent is None:
        st, ref = api(token, "GET", f"/repos/{repo}/git/ref/heads/{branch}")
        if st == 200 and ref:
            parent = ref["object"]["sha"]
    if parent:
        print(f"  父提交 {parent[:8]}")

    blobs = []
    for rel in files:
        content = read_for_git(rel)
        st, res = api(token, "POST", f"/repos/{repo}/git/blobs",
                      {"content": base64.b64encode(content).decode("ascii"), "encoding": "base64"})
        if st not in (200, 201):
            print(f"  blob 失败 {rel}: {st} {res}")
            return 1
        blobs.append({"path": rel, "mode": "100644", "type": "blob", "sha": res["sha"]})
        print(f"  blob {res['sha'][:8]}  {rel}  ({len(content)} 字节)")

    if dry:
        print("\n--dry-run：未创建 tree/commit")
        return 0

    # 不带 base_tree：每次都是完整快照，避免历史残留混进来
    st, tree = api(token, "POST", f"/repos/{repo}/git/trees", {"tree": blobs})
    if st not in (200, 201):
        print(f"建 tree 失败 {st}: {tree}")
        return 1

    message = git("log", "-1", "--format=%B").strip() or "更新"
    st, commit = api(token, "POST", f"/repos/{repo}/git/commits",
                     {"message": message, "tree": tree["sha"],
                      "parents": [parent] if parent else []})
    if st not in (200, 201):
        print(f"建 commit 失败 {st}: {commit}")
        return 1
    print(f"commit: {commit['sha']}")

    st, ref = api(token, "GET", f"/repos/{repo}/git/ref/heads/{branch}")
    if st == 200 and ref:
        st, res = api(token, "PATCH", f"/repos/{repo}/git/refs/heads/{branch}",
                      {"sha": commit["sha"], "force": True})
    else:
        st, res = api(token, "POST", f"/repos/{repo}/git/refs",
                      {"ref": f"refs/heads/{branch}", "sha": commit["sha"]})
    if st not in (200, 201):
        print(f"更新 ref 失败 {st}: {res}")
        return 1

    subprocess.run(["git", "update-ref", f"refs/remotes/origin/{branch}", commit["sha"]],
                   cwd=ROOT, capture_output=True, timeout=30)
    print(f"\n推送成功：https://github.com/{repo}/tree/{branch}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
