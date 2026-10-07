// 相似度推荐算法（从 Similarity.kt 移植）
// 核心目的：用户输入新单词，自动推荐可能混淆的现有词组，点一下并入该组。

export function normalizeWordKey(word: string | null | undefined): string {
  if (!word) return ''
  return word
    .toLowerCase()
    .replace(/’/g, "'")
    .replace(/‘/g, "'")
    .replace(/\s+/g, ' ')
    .trim()
}

export function levenshtein(a: string, b: string): number {
  if (a === b) return 0
  if (a.length === 0) return b.length
  if (b.length === 0) return a.length

  let prev = Array.from({ length: b.length + 1 }, (_, i) => i)
  for (let i = 1; i <= a.length; i++) {
    const ca = a[i - 1]
    const cur = new Array(b.length + 1)
    cur[0] = i
    for (let j = 1; j <= b.length; j++) {
      const cb = b[j - 1]
      const cost = ca === cb ? 0 : 1
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
    }
    prev = cur
  }
  return prev[b.length]
}

export function commonPrefixLen(a: string, b: string): number {
  const n = Math.min(a.length, b.length)
  let i = 0
  while (i < n && a[i] === b[i]) {
    i++
  }
  return i
}

export function commonSuffixLen(a: string, b: string): number {
  const n = Math.min(a.length, b.length)
  let i = 0
  while (i < n && a[a.length - 1 - i] === b[b.length - 1 - i]) {
    i++
  }
  return i
}

export function similarity(a: string, b: string): number {
  const na = normalizeWordKey(a)
  const nb = normalizeWordKey(b)
  if (!na || !nb) return 0.0
  if (na === nb) return 1.0

  const dist = levenshtein(na, nb)
  const longest = Math.max(na.length, nb.length)
  const editScore = 1.0 - dist / longest
  const pre = commonPrefixLen(na, nb) / longest
  const suf = commonSuffixLen(na, nb) / longest
  const firstBonus = na[0] === nb[0] ? 0.18 : 0.0

  const score = 0.55 * editScore + 0.25 * pre + 0.1 * suf + firstBonus
  return Math.max(0.0, Math.min(1.0, score))
}

export interface GroupCandidate {
  groupId: string
  groupName: string
  score: number
  matchedWord: string
  allWords: string[]
}

export function findSimilarGroups(
  word: string,
  groups: Array<{ id: string; name: string; entries: Array<{ word: string }> }>
): GroupCandidate[] {
  const cleanWord = normalizeWordKey(word)
  if (cleanWord.length < 2) return []

  const candidates: GroupCandidate[] = []

  for (const g of groups) {
    let bestScore = similarity(cleanWord, g.name)
    let bestWord = g.name

    const groupWords = (g.entries || []).map((e) => e.word)
    for (const w of groupWords) {
      const s = similarity(cleanWord, w)
      if (s > bestScore) {
        bestScore = s
        bestWord = w
      }
    }

    if (bestScore >= 0.45) {
      candidates.push({
        groupId: g.id,
        groupName: g.name,
        score: bestScore,
        matchedWord: bestWord,
        allWords: groupWords.length > 0 ? groupWords : [g.name],
      })
    }
  }

  candidates.sort((a, b) => b.score - a.score)
  return candidates.slice(0, 6)
}
