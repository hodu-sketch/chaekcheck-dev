// Korean particles that depend on the final consonant of the previous word.

function lastHangulFinal(word: string): number | null {
  const last = word.trim().at(-1)
  if (!last) return null
  const code = last.charCodeAt(0)
  if (code < 0xac00 || code > 0xd7a3) return null
  return (code - 0xac00) % 28
}

/** "마포구가", "서울 중구가", "강서구가" / "고흥군이" */
export function subject(word: string): string {
  const final = lastHangulFinal(word)
  return word + (final === null || final === 0 ? '가' : '이')
}

/** "마포구로", "고흥군으로", "서울로" (ㄹ takes 로) */
export function direction(word: string): string {
  const final = lastHangulFinal(word)
  return word + (final === null || final === 0 || final === 8 ? '로' : '으로')
}
