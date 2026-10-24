import type { PicksListKey } from '../../api/types'
import { PICKS_LISTS } from './picksLists'

/** The words of 「나에게 맞는 책」, all [확정] (cycle 23 handoff 문구표). */
export const FOR_YOU_TEXT = {
  aiLine: '책 소개 글과 꼬리표는 AI가 골랐어요',
  changeBase: '기준 책 바꾸기',
  more: '비슷한 책 더 보기',
  similarFailed: '비슷한 책을 불러오지 못했어요',
  baseFailed: '추천 책을 불러오지 못했어요',
  retry: '다시 시도',
  noneInMyLibraries: '내 도서관에 있는 추천 책이 없어요',
  /** [안] the line to 마이페이지 under the empty state */
  toMyPage: '마이페이지에서 지역과 도서관 바꾸기',
  remove: '빼기',
  clearAll: '최근 본 책 기록 모두 지우기',
  /** [안] S19's title and its mark on the current base books */
  dialogTitle: '기준 책 바꾸기',
  baseMark: '기준 책',
  /** [안] screen readers while loading */
  loading: '추천 책을 불러오는 중',
  helpLines: [
    '요즘 전자도서관에서 많이 빌리는 책과 판매 순위, 추천목록을 섞었어요',
    '최근 본 책은 이 브라우저에만 저장돼요',
    '주제 분류: Thema (EDItEUR)',
  ],
} as const

/** 「내 도서관 N곳」 (R4-1). */
export function myLibrariesText(count: number): string {
  return `내 도서관 ${count}곳`
}

/** "○○ 추천목록에 있어요" with the tab names; past two, the first two and "외 N" (R3-2, AC-3). */
export function listsFooter(lists: readonly PicksListKey[]): string | null {
  const names = lists.filter((key) => Object.hasOwn(PICKS_LISTS, key)).map((key) => PICKS_LISTS[key].tabLabel)
  if (names.length === 0) return null
  const shown = names.slice(0, 2).join('·')
  const rest = names.length - 2
  return `${shown}${rest > 0 ? ` 외 ${rest}` : ''} 추천목록에 있어요`
}

/** "최근 본 책 ○○·○○·○○과 비슷한 책": 과 after a closed syllable, 와 otherwise. */
export function baseLine(titles: readonly string[]): string {
  const joined = titles.join('·')
  return `최근 본 책 ${joined}${withParticle(joined)} 비슷한 책`
}

function withParticle(text: string): string {
  const last = text.trim().at(-1) ?? ''
  const code = last.charCodeAt(0)
  if (code >= 0xac00 && code <= 0xd7a3) return (code - 0xac00) % 28 === 0 ? '와' : '과'
  // not Hangul (a Latin letter or a digit at the end): 과 reads for most
  return '과'
}
