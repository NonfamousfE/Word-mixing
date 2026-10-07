import re
from typing import List, Dict, Any
from sqlalchemy.orm import Session
from models import Group, WordEntry


def normalize_word_key(word: str) -> str:
    if not word:
        return ""
    w = word.lower()
    w = w.replace("’", "'").replace("‘", "'")
    w = re.sub(r"\s+", " ", w)
    return w.strip()


def levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    if not a:
        return len(b)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        ca = a[i - 1]
        cur = [i] * (len(b) + 1)
        for j in range(1, len(b) + 1):
            cb = b[j - 1]
            cost = 0 if ca == cb else 1
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        prev = cur
    return prev[len(b)]


def common_prefix_len(a: str, b: str) -> int:
    n = min(len(a), len(b))
    i = 0
    while i < n and a[i] == b[i]:
        i += 1
    return i


def common_suffix_len(a: str, b: str) -> int:
    n = min(len(a), len(b))
    i = 0
    while i < n and a[len(a) - 1 - i] == b[len(b) - 1 - i]:
        i += 1
    return i


def similarity(a: str, b: str) -> float:
    na = normalize_word_key(a)
    nb = normalize_word_key(b)
    if not na or not nb:
        return 0.0
    if na == nb:
        return 1.0

    dist = levenshtein(na, nb)
    longest = float(max(len(na), len(nb)))
    edit_score = 1.0 - (dist / longest)
    pre = common_prefix_len(na, nb) / longest
    suf = common_suffix_len(na, nb) / longest
    first_bonus = 0.18 if na[0] == nb[0] else 0.0

    score = 0.55 * edit_score + 0.25 * pre + 0.10 * suf + first_bonus
    return max(0.0, min(1.0, score))


def find_similar_groups(
    db: Session,
    user_id: int,
    word: str,
    limit: int = 8,
    min_score: float = 0.34
) -> List[Dict[str, Any]]:
    key = normalize_word_key(word)
    if len(key) < 2:
        return []

    # 查询该用户所有的分组及词条
    groups = db.query(Group).filter(Group.user_id == user_id).all()
    if not groups:
        return []

    candidates = []

    for g in groups:
        best_score = similarity(key, g.name)
        best_word = g.name
        best_ipa = ""
        entries = g.entries or []
        group_words = [e.word for e in entries]

        for e in entries:
            s = similarity(key, e.word)
            if common_prefix_len(key, normalize_word_key(e.word)) >= 3:
                s = max(s, 0.4)
            if s > best_score:
                best_score = s
                best_word = e.word
                best_ipa = e.phonetic or ""

        if best_score >= min_score:
            sibs = [w for w in group_words if normalize_word_key(w) != normalize_word_key(best_word)]
            candidates.append({
                "groupId": g.id,
                "groupName": g.name,
                "score": round(best_score, 3),
                "matchedWord": best_word,
                "matchedIpa": best_ipa,
                "siblings": sibs[:3],
                "allWords": group_words if group_words else [g.name]
            })

    candidates.sort(key=lambda c: c["score"], reverse=True)
    return candidates[:limit]
