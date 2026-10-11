/** Books per page of a category: a 3 x 3 grid (R4-1). */
export const PAGE_SIZE = 9

/** [확정] (R7-1): the source and the stores it adds up; the month and the fetch day follow it. */
export const SOURCE_TEXT = '출처: 출판유통통합전산망 (교보문고·예스24·알라딘·영풍문고·지역서점 종이책 판매 합산)'

/** [확정] (R6-9; the wording is looked at again on the screen check, step 8). */
export const REQUEST_TEXT = '아직 내 도서관에 전자책이 없어요. 이용하는 전자도서관 사이트에서 희망도서로 신청해 보세요.'

/** [확정] (flow.md S13i). */
export const HELP_LINES = [
  '종이책 순위라서 전자책이 없는 책도 있어요',
  '흐린 책은 내 도서관에 전자책이 없어요',
  '있음/없음은 검색 화면에서 고른 지역·범위 기준이에요',
] as const

/** "2026-09" → "2026년 9월"; null when the value has another shape. */
export function monthLabel(month: string | null): string | null {
  const match = month?.match(/^(\d{4})-(\d{2})$/)
  return match ? `${match[1]}년 ${Number(match[2])}월` : null
}

/** "2026-10-03T05:01:32+09:00" → "10월 3일 받음" (the day in Korea); null when not a date. */
export function fetchedLabel(fetchedAt: string | null): string | null {
  if (!fetchedAt) return null
  const date = new Date(fetchedAt)
  if (Number.isNaN(date.getTime())) return null
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric' }).formatToParts(date)
  const month = parts.find((p) => p.type === 'month')?.value
  const day = parts.find((p) => p.type === 'day')?.value
  return month && day ? `${month}월 ${day}일 받음` : null
}

/** The source line under the last category (R7-1): "출처: … · 2026년 9월 · 10월 3일 받음". */
export function sourceLine(month: string | null, fetchedAt: string | null): string {
  return [SOURCE_TEXT, monthLabel(month), fetchedLabel(fetchedAt)].filter(Boolean).join(' · ')
}

/** How many 9-book pages a category has (50 books: 6, the last with 5). */
export function pageCount(books: number): number {
  return Math.max(1, Math.ceil(books / PAGE_SIZE))
}

/** Only our own cover address is used as an image source (C7); anything else shows the title. */
export function safeCoverUrl(url: string | null): string | null {
  return url && /^\/api\/v1\/popular-books\/covers\/\d+$/.test(url) ? url : null
}

/** The id of a category's block, for moving the page to it (S13c). */
export function sectionId(code: string): string {
  return `popular-${code}`
}
