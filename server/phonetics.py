"""音标：把 CMUdict 的 ARPAbet 发音转成 IPA。

数据来自公开的 CMU 发音词典（build/fetch-phonetics.py 下载后随程序打包）。
转换规则写在这里而不是预先算好，是为了以后能随时调整（比如切换英美音标）。

ARPAbet 说明：元音后的数字表示重音位置 —— 1 主重音、2 次重音、0 无重音。
"""

from __future__ import annotations

import gzip
import importlib.resources
import os
import pkgutil
import re

# 数据文件随包分发。打包成 zipapp（.pyz）后它位于压缩包内部，
# 这时**不能**用 gzip.open(path) —— 内建 open() 打不开 zip 里的嵌套路径，
# 必须走 pkgutil.get_data / importlib.resources 才能读出来（踩过这个坑：
# 源码运行时音标正常，一打包就全空）。
_PKG = __name__.rsplit(".", 1)[0] if "." in __name__ else "wordmix"
_DATA_REL = "data/phonetic.txt.gz"
_DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "phonetic.txt.gz")

# 美式 IPA 基础映射
_IPA_US = {
    "AA": "ɑ",
    "AE": "æ",
    "AH": "ʌ",
    "AO": "ɔ",
    "AW": "aʊ",
    "AY": "aɪ",
    "EH": "ɛ",
    "ER": "ɝ",
    "EY": "eɪ",
    "IH": "ɪ",
    "IY": "i",
    "OW": "oʊ",
    "OY": "ɔɪ",
    "UH": "ʊ",
    "UW": "u",
    "B": "b",
    "CH": "tʃ",
    "D": "d",
    "DH": "ð",
    "F": "f",
    "G": "ɡ",
    "HH": "h",
    "JH": "dʒ",
    "K": "k",
    "L": "l",
    "M": "m",
    "N": "n",
    "NG": "ŋ",
    "P": "p",
    "R": "ɹ",
    "S": "s",
    "SH": "ʃ",
    "T": "t",
    "TH": "θ",
    "V": "v",
    "W": "w",
    "Y": "j",
    "Z": "z",
    "ZH": "ʒ",
}

_STRESS_MARK = {"1": "ˈ", "2": "ˌ", "0": ""}

_VOWELS = {"AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW"}

# 英式差异（尽量贴近剑桥/朗文那种写法）
_IPA_UK_OVERRIDE = {
    "ER": "ɜː",   # 英式非儿化
    "AA": "ɒ",    # lot 元音
    "AO": "ɔː",
    "IY": "iː",
    "UW": "uː",
}
_UK_LONG = {"AA", "AO", "IY", "UW", "ER", "AO"}


def _read_data_bytes() -> bytes | None:
    """把词典数据读成字节。兼容"普通目录"和"zipapp 内部"两种运行方式。"""
    # 1) 常规安装 / 源码运行：直接读文件
    try:
        if os.path.isfile(_DATA):
            with open(_DATA, "rb") as fh:
                return fh.read()
    except OSError:
        pass

    # 2) 打包成 zipapp：走 importlib.resources（3.9+ 的 files() API）
    try:
        res = importlib.resources.files(_PKG).joinpath(_DATA_REL)
        return res.read_bytes()
    except (FileNotFoundError, ModuleNotFoundError, AttributeError, TypeError, OSError):
        pass

    # 3) 兜底：pkgutil 走 loader.get_data，zipimporter 支持
    try:
        blob = pkgutil.get_data(_PKG, _DATA_REL)
        if blob:
            return blob
    except (OSError, ImportError, ValueError):
        pass

    # 4) 最后一招：把 __file__ 里的 "xxx.pyz/..." 切开，用 zipfile 取
    try:
        here = os.path.abspath(__file__)
        idx = here.find(".pyz" + os.sep)
        if idx >= 0:
            import zipfile

            archive = here[: idx + 4]
            inner = here[idx + 5:].replace(os.sep, "/")
            inner = os.path.dirname(inner) + "/" + _DATA_REL
            with zipfile.ZipFile(archive) as zf:
                return zf.read(inner)
    except (OSError, KeyError, ValueError):
        pass
    return None


def _load_table() -> dict[str, str]:
    table: dict[str, str] = {}
    blob = _read_data_bytes()
    if not blob:
        return table
    try:
        text = gzip.decompress(blob).decode("utf-8")
    except (OSError, EOFError):
        return table
    for line in text.splitlines():
        if not line:
            continue
        parts = line.split("\t", 1)
        if len(parts) == 2:
            table[parts[0]] = parts[1]
    return table


_TABLE: dict[str, str] | None = None


def _table() -> dict[str, str]:
    global _TABLE
    if _TABLE is None:
        _TABLE = _load_table()
    return _TABLE


def available() -> bool:
    return bool(_table())


def lookup(word: str) -> str | None:
    """查 ARPAbet 发音（大写，含重音数字）。"""
    return _table().get(str(word or "").strip().lower())


_STRIP_RE = re.compile(r"[^a-z'\-\.]")


def _clean(word: str) -> str:
    return _STRIP_RE.sub("", str(word or "").strip().lower())


def _normalize_r(word: str) -> str:
    """把英美拼写差异归一到词典里的形式。"""
    if word in _table():
        return word
    alt = word
    if alt.endswith("our"):
        alt = alt[:-3] + "or"
    if alt.endswith("ise"):
        alt = alt[:-3] + "ize"
    if alt.endswith("isation"):
        alt = alt[:-7] + "ization"
    if alt.endswith("re") and len(alt) > 3:
        alt = alt[:-2] + "er"
    if alt.endswith("lled"):
        alt = alt[:-4] + "led"
    return alt if alt in _table() else word


def ipa(word: str, accent: str = "uk") -> str:
    """返回 /.../ 形式的 IPA；词典里没有就返回空串。"""
    w = _normalize_r(_clean(word))
    pron = lookup(w)
    if not pron:
        # 连字符/复合词：逐段拼
        if "-" in w:
            parts = [ipa(p, accent) for p in w.split("-") if p]
            joined = "".join(p.strip("/") for p in parts if p)
            return f"/{joined}/" if joined else ""
        return ""
    return f"/{_to_ipa(pron, accent)}/"


def _drop_spurious_stress(tokens: list[str]) -> list[str]:
    """去掉"紧邻主重音之前的那个次重音"。

    CMUdict 对某些双音节词会标成 IH2 M P OW1（如 impose）、IH2 N D IH1（如 indignant），
    也就是把次重音加在紧挨主重音的前一个音节上 —— 但实际发音里那个音节是弱读的。
    照搬会得到 ˌɪˈmpoʊz 这种明显不自然的写法，所以这里把它规整掉。
    真正的次重音（前面隔了不止一个音节，或有元音间隔）会保留，例如
   ˌʌndərˈstænd。
    """
    out: list[str] = []
    n = len(tokens)
    for i, tok in enumerate(tokens):
        m = re.match(r"^([A-Z]+)([0-2])?$", tok)
        if not m:
            out.append(tok)
            continue
        phon, stress = m.group(1), m.group(2)
        if stress == "2" and phon in _VOWELS:
            # 往后找下一个重读元音
            j = i + 1
            nxt = None
            while j < n:
                mm = re.match(r"^([A-Z]+)([0-2])?$", tokens[j])
                if mm and mm.group(1) in _VOWELS:
                    nxt = mm.group(2)
                    break
                if mm and mm.group(2) in ("1", "2"):
                    nxt = mm.group(2)
                    break
                j += 1
            if nxt == "1":
                # 中间若还有别的元音，说明是真正的次重音，保留
                between_vowel = False
                for k in range(i + 1, j):
                    mk = re.match(r"^([A-Z]+)([0-2])?$", tokens[k])
                    if mk and mk.group(1) in _VOWELS:
                        between_vowel = True
                        break
                if not between_vowel:
                    out.append(phon)  # 降级为无重音
                    continue
        out.append(tok)
    return out


def _to_ipa(pron: str, accent: str) -> str:
    """ARPAbet -> IPA。

    要点：重音符号要标在**整个音节**前面（含音节开头的辅音），
    而不是直接贴在元音前 —— 否则 cleɪv 会写成 plˈeɪɡ 这种错误形式。
    做法：先把音素逐个转好但把重音标记暂存，遇到主/次重音时回头
    插到"该音节起始辅音"之前。
    """
    items: list[tuple[str, str]] = []  # (IPA 文本, 音素名)
    tokens = _drop_spurious_stress(pron.split())

    for i, tok in enumerate(tokens):
        m = re.match(r"^([A-Z]+)([0-2])?$", tok)
        if not m:
            continue
        phon, stress = m.group(1), m.group(2) or "0"
        is_vowel = phon in _VOWELS

        if is_vowel:
            if accent == "uk":
                if stress == "0" and phon == "AH":
                    sym = "ə"
                else:
                    sym = _IPA_UK_OVERRIDE.get(phon, _IPA_US.get(phon, phon.lower()))
                    if stress == "0" and phon in _UK_LONG:
                        sym = {"AA": "ə", "AO": "ə", "IY": "i", "UW": "u", "ER": "ə"}.get(phon, sym)
            else:
                sym = _IPA_US.get(phon, phon.lower())
                if stress == "0" and phon == "AH":
                    sym = "ə"
            mark = _STRESS_MARK.get(stress, "") if stress in ("1", "2") else ""
            items.append((sym, phon, mark))
        else:
            if accent == "uk" and phon == "R":
                # 英式非儿化：元音后的 R 不发音
                prev_is_vowel = False
                for j in range(i - 1, -1, -1):
                    pm = re.match(r"^([A-Z]+)", tokens[j])
                    if pm:
                        prev_is_vowel = pm.group(1) in _VOWELS
                        break
                if prev_is_vowel:
                    continue
            items.append((_IPA_US.get(phon, phon.lower()), phon, ""))

    # 回填重音。注意顺序：先在纯文本里确定每个音节的起始位置，
    # 再从右往左插入标记 —— 否则后插入的标记会把先前插入的位置挤偏。
    # 同一个音节里既有次重音又有主重音时只保留主重音：CMUdict 有些词
    # （如 impose = IH2 M P OW1 Z）会把次重音标在紧邻主重音的位置，
    # 照搬会写出 ˌɪˈmpoʊz 这种怪东西，实际应是 ɪmˈpoʊz。
    plain: list[tuple[str, str]] = [(sym, phon) for sym, phon, _ in items]
    marks_at: dict[int, set[str]] = {}
    for idx, (_sym, phon, mark) in enumerate(items):
        if not mark:
            continue
        pos = idx
        j = idx - 1
        while j >= 0 and plain[j][1] not in _VOWELS:
            pos = j
            j -= 1
        marks_at.setdefault(pos, set()).add(mark)

    out: list[str] = []
    for i, (sym, _phon) in enumerate(plain):
        marks = marks_at.get(i)
        if marks:
            out.append("ˈ" if "ˈ" in marks else "ˌ")
        out.append(sym)
    return "".join(out)


def ipa_us(word: str) -> str:
    return ipa(word, "us")


def stats() -> dict:
    """给「关于」对话框显示用。"""
    t = _table()
    return {"words": len(t), "file": _DATA, "exists": os.path.exists(_DATA)}


if __name__ == "__main__":
    import sys

    s = stats()
    print(f"词典: {s['words']} 个词  文件存在={s['exists']}")
    words = sys.argv[1:] or [
        "garment", "plague", "cliff", "bubble", "impetus", "impose",
        "obstruct", "indignant", "office", "greengrocer", "colour", "centre",
    ]
    for w in words:
        print(f"  {w:12} 英 {ipa(w, 'uk'):22} 美 {ipa(w, 'us')}")
