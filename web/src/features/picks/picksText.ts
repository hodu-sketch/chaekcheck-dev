/** Books per page of a category: a 3 x 3 grid (R4-1). */
export const PAGE_SIZE = 9

/** [확정] (cycle 14 R7-1): the source and its licence; the fetch day follows it. */
export const SOURCE_TEXT = '출처: 국립중앙도서관 사서추천도서 (공공누리 제1유형)'

/**
 * S15 in two lines (R6-9). "검색하는 도서관", not "내 도서관": before login (or with 전체 도서관) it is not "my" libraries
 * (user screen review 2026-10-12: "'아직 내 도서관에 전자책이 없어요.' 이런 설명 다음에 '이용하는 전자도서관 사이트에서
 * 희망도서로 신청해 보세요.' 이건 개행했으면 해").
 */
export const REQUEST_LINES = [
  '아직 검색하는 도서관에 전자책이 없어요.',
  '이용하는 전자도서관 사이트에서 희망도서로 신청해 보세요.',
] as const

/**
 * (cycle 14 flow.md S13i) with the user's changes of 2026-10-12: "최근 12번(2년)" (the flow had "6번") and how often the
 * library recommends (seen in the 2-year answer: every other month 2 books in each of the four fields, theme months
 * 12 books). The source line follows them.
 */
export const HELP_LINES = [
  '국립중앙도서관 사서가 고른 신간 추천 도서 중 최근 12번(2년) 추천분이에요',
  '보통 2달에 한 번, 한 번에 8권(문학·인문·사회·자연 각 2권)을 추천해요. 테마 추천 때는 12권이에요',
  '신간이라 아직 전자책이 없는 책도 있어요',
  '흐린 책은 검색하는 도서관에 전자책이 없어요',
  '있음/없음은 검색 화면에서 고른 지역 기준이에요',
] as const

/** "2026-10" → "2026년 10월" (S14's recommendation line); null when the value has another shape. */
export function monthLabel(month: string | null): string | null {
  const match = month?.match(/^(\d{4})-(\d{2})$/)
  return match ? `${match[1]}년 ${Number(match[2])}월` : null
}

/** "2026년 10월 사서추천 · 문학" (cycle 14 flow.md S14): the month, then the field name. */
export function pickLine(recommendedMonth: string, categoryName: string | null): string {
  const month = monthLabel(recommendedMonth)
  return [month ? `${month} 사서추천` : '사서추천', categoryName].filter(Boolean).join(' · ')
}

/** "2026-10-12T05:00:41+09:00" → "10월 12일 받음" (the day in Korea); null when not a date. */
export function fetchedLabel(fetchedAt: string | null): string | null {
  if (!fetchedAt) return null
  const date = new Date(fetchedAt)
  if (Number.isNaN(date.getTime())) return null
  const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric' }).formatToParts(date)
  const month = parts.find((p) => p.type === 'month')?.value
  const day = parts.find((p) => p.type === 'day')?.value
  return month && day ? `${month}월 ${day}일 받음` : null
}

/** The source line under the last field (R7-1): "출처: 국립중앙도서관 사서추천도서 (공공누리 제1유형) · 10월 12일 받음". */
export function sourceLine(fetchedAt: string | null): string {
  return [SOURCE_TEXT, fetchedLabel(fetchedAt)].filter(Boolean).join(' · ')
}

/** How many 9-book pages a field has (10 books: 2, the last with 1). */
export function pageCount(books: number): number {
  return Math.max(1, Math.ceil(books / PAGE_SIZE))
}

/**
 * The title shown on a card without a cover: the part before the first " : " or " = " (librarian picks carry long
 * subtitles, "시간을 읽는 그림 : 수천 년 세계사를 담은 기록의 그림들"); the whole title when nothing is left.
 */
export function cardTitle(title: string): string {
  const main = title.split(/\s+[:=]\s+/)[0].trim()
  return main || title
}

/** Only our own cover address is used as an image source (C7); anything else shows the title. */
export function safeCoverUrl(url: string | null): string | null {
  return url && /^\/api\/v1\/picks\/covers\/\d+$/.test(url) ? url : null
}

/** The id of a category's block, for moving the page to it (S13c). */
export function sectionId(code: string): string {
  return `picks-${code}`
}
