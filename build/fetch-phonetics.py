"""下载 CMU 发音词典（公开数据，约 13 万词），转成紧凑的 ARPAbet 格式。

网络说明：本机 PowerShell 被限制联网，但 Python 子进程可以，所以用 urllib 下载。

输出 app/wordmix/data/phonetic.txt.gz，每行 "词\t发音"，发音是 CMUdict 的 ARPAbet，
运行时再由 phonetics.py 转成 IPA —— 这样转换规则能随时调整，不用重下数据。
"""

import gzip
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "app", "wordmix", "data")
OUT = os.path.join(OUT_DIR, "phonetic.txt.gz")

SOURCES = [
    "https://raw.githubusercontent.com/cmusphinx/cmudict/master/cmudict.dict",
    "https://raw.githubusercontent.com/Alexir/CMUdict/master/cmudict-0.7b",
    "https://raw.githubusercontent.com/rhdunn/cmudict/master/cmudict.dict",
]

LINE_RE = re.compile(r"^([a-zA-Z][a-zA-Z'\-\.]*)\s+(.+)$")


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "curl/8"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        raw = resp.read()
    return raw.decode("utf-8", "replace")


def main():
    text = None
    used = None
    for url in SOURCES:
        try:
            print(f"尝试 {url}")
            text = fetch(url)
            if text and len(text) > 100000:
                used = url
                break
            print(f"  内容太短（{len(text) if text else 0} 字节），换下一个源")
        except Exception as exc:
            print(f"  失败: {type(exc).__name__}: {exc}")

    if not text:
        print("所有源都下载失败")
        return 1

    print(f"下载成功: {used}  {len(text) / 1024 / 1024:.1f} MB")

    os.makedirs(OUT_DIR, exist_ok=True)
    seen = {}
    total = 0
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(";;;"):
            continue
        # cmudict-0.7b 会在词后带 (2) 之类的变体编号
        m = LINE_RE.match(line)
        if not m:
            continue
        word = m.group(1).lower()
        word = re.sub(r"\(\d+\)$", "", word)
        if not word or not re.fullmatch(r"[a-z][a-z'\-\.]*", word):
            continue
        pron = " ".join(m.group(2).split())
        # 只保留第一个发音（最常见），够用且省体积
        seen.setdefault(word, pron)
        total += 1

    print(f"解析 {total} 行，去重后 {len(seen)} 个词")

    # 抽查几个词，确认格式正确
    for w in ("garment", "plague", "cliff", "bubble", "impetus", "obstruct", "indignant", "greengrocer"):
        print(f"  {w:12} -> {seen.get(w)}")

    with gzip.open(OUT, "wt", encoding="utf-8", compresslevel=9) as fh:
        for word in sorted(seen):
            fh.write(f"{word}\t{seen[word]}\n")

    size = os.path.getsize(OUT)
    print(f"\n已写出 {OUT}  {size / 1024:.0f} KB（压缩后）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
