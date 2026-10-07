"""一键跑齐服务端测试套件与健康自检。

用法：
  python build/run-all-tests.py
"""

import os
import subprocess
import sys
import time

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

TESTS = [
    ("全栈服务端与业务API测试", "server/test_api.py"),
]

CLEAN = [
    "tmp-server-test",
    "tmp-smoke",
    "tmp-stale",
]


def clean():
    import shutil

    for name in CLEAN:
        target = os.path.join(ROOT, name)
        base = os.path.basename(target)
        if not base.startswith("tmp-"):
            continue
        if os.path.isdir(target):
            shutil.rmtree(target, ignore_errors=True)
        elif os.path.exists(target):
            try:
                os.remove(target)
            except OSError:
                pass


def run(path: str) -> tuple[int, int, str]:
    full_path = os.path.join(ROOT, path)
    if not os.path.exists(full_path):
        return 0, 0, "(跳过，文件不存在)"
    env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUNBUFFERED="1")
    r = subprocess.run(
        [sys.executable, "-X", "utf8", "-u", full_path],
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=env,
        timeout=300,
    )
    out = (r.stdout or "") + (r.stderr or "")
    passed = failed = 0
    summary = ""
    for line in out.splitlines():
        s = line.strip()
        if "通过" in s:
            summary = s
            passed = 1
        elif "失败" in s or "Error" in s:
            failed = 1
    return passed, failed, summary or ("完成" if r.returncode == 0 else "失败")


def main():
    print("=" * 60)
    print("  单词混记 · 服务端自动化测试")
    print("=" * 60)

    clean()
    total_pass = total_fail = 0

    for name, path in TESTS:
        t0 = time.time()
        p, f, s = run(path)
        total_pass += p
        total_fail += f
        print(f"  {name:<24} {s:<24} ({time.time() - t0:.1f}s)")

    clean()
    print("=" * 60)
    print(f"  合计：通过 {total_pass} 项，失败 {total_fail} 项")
    print("=" * 60)
    return 1 if total_fail else 0


if __name__ == "__main__":
    sys.exit(main())
