import type { PickBook, PicksListKey } from '../../api/types'
import { HELP_LINES, sourceLine } from './picksText'

/** The YouTube Terms of Service, linked from Lee Dong-jin's source line (cycle 21 spec R5-5) [확정]. */
export const YOUTUBE_TERMS_URL = 'https://www.youtube.com/t/terms'

/** A video id as the API gives it ([안] CHECK `^[A-Za-z0-9_-]{1,64}$`, observed 11 characters). */
const VIDEO_ID = /^[A-Za-z0-9_-]{1,64}$/

/** What a list's screen says and shows (cycle 21 spec R5, R6, R7). */
export interface PicksList {
  key: PicksListKey
  /** The list's name: the menu item and the heading (design A). */
  name: string
  /** The tab's words at the top of the screen (user 2026-10-19 redesign). */
  tabLabel: string
  /** While the list is not stored yet (R7-1). */
  emptyText: string
  /** When the screen's request failed (R7-1). */
  failedText: string
  /** For screen readers while loading. */
  loadingText: string
  /** "추천 기준과 출처 보기" (S13i): the criteria, then the source line. */
  helpLines: readonly string[]
  /** The source line in "추천 기준과 출처 보기"; the YouTube terms link follows Lee Dong-jin's. */
  source: (fetchedAt: string | null) => string
  /** The book windows' line of when the book was recommended ("2025년 KAIST 사서추천"), for KAIST only. */
  yearLine?: (book: PickBook) => string | null
}

/** [확정] cycle 14: the librarian picks keep their words ("사서추천도서", AC-10). */
const LIBRARIAN: PicksList = {
  key: 'librarian',
  name: '사서 추천도서',
  // told apart from KAIST's 사서추천도서 (user 2026-10-20: "기존의 "사서 추천"을 "국립중앙도서관 사서 추천" 으로 바꿔줘")
  tabLabel: '국립중앙도서관 사서 추천',
  emptyText: '아직 사서추천도서를 준비하고 있어요',
  failedText: '사서추천도서를 불러오지 못했어요. 잠시 뒤에 다시 해 주세요.',
  loadingText: '사서추천도서를 불러오는 중',
  helpLines: HELP_LINES,
  source: sourceLine,
}

const LEEDONGJIN: PicksList = {
  key: 'leedongjin',
  name: '이동진 추천도서',
  tabLabel: '이동진 추천',
  emptyText: '아직 이동진 추천도서를 준비하고 있어요',
  failedText: '이동진 추천도서를 불러오지 못했어요. 잠시 뒤에 다시 해 주세요.',
  loadingText: '이동진 추천도서를 불러오는 중',
  helpLines: [
    '이동진 평론가가 유튜브 「B tv 이동진의 파이아키아」에서 고른 이달의 책과 올해의 책이에요',
    '책첵은 이동진 평론가·파이아키아와 관계가 없어요',
    '“전자책 없음” 책은 검색하는 도서관에 전자책이 없어요',
  ],
  source: () => '출처: 유튜브 「B tv 이동진의 파이아키아」 · 책첵은 이동진 평론가·파이아키아와 관계가 없어요',
}

const SNU100: PicksList = {
  key: 'snu100',
  name: '서울대 100선',
  tabLabel: '서울대 100선',
  emptyText: '아직 서울대 100선을 준비하고 있어요',
  failedText: '서울대 100선을 불러오지 못했어요. 잠시 뒤에 다시 해 주세요.',
  loadingText: '서울대 100선을 불러오는 중',
  helpLines: [
    '서울대학교 기초교육원이 펴낸 『권장도서 해제집』의 100권이에요',
    '판이 여러 개인 책은 가장 많은 도서관이 가진 판을 보여 드려요',
    '“전자책 없음” 책은 검색하는 도서관에 전자책이 없어요',
  ],
  source: () => '출처: 서울대학교 기초교육원 『권장도서 해제집』(서울대학교출판문화원)',
}

/**
 * KAIST library's 사서추천도서 of 2026, 2025 and 2024 (user 2026-10-19: "이동진 추천 빼고 여기 넣어줘"; 2026-10-20:
 * "사서추천도서로 바꿔줘 … 스페셜 도서는 결과가 너무 없어", "2026, 2025, 2024 다 해줘", "몇 년도 추천인지 이 창에서
 * 나오도록"); the fields by the book's subject (4-picks.md 7절).
 */
const KAIST: PicksList = {
  key: 'kaist',
  name: 'KAIST 사서 추천도서',
  // user 2026-10-20: "카이스트 추천 이걸 카이스트 사서 추천으로 바꿔줘"
  tabLabel: 'KAIST 사서 추천',
  emptyText: '아직 KAIST 사서 추천도서를 준비하고 있어요',
  failedText: 'KAIST 사서 추천도서를 불러오지 못했어요. 잠시 뒤에 다시 해 주세요.',
  loadingText: 'KAIST 사서 추천도서를 불러오는 중',
  helpLines: [
    'KAIST 도서관 사서가 고른 「사서추천도서」 국내도서 2026·2025·2024년이에요. 최근 해부터 보여 드려요',
    '분류는 책의 주제에 따라 책첵이 나눴어요',
    '책첵은 KAIST 도서관과 관계가 없어요',
  ],
  source: () => '출처: KAIST 도서관 소장자료검색 · Collections · 추천도서 · 사서추천도서 · 책첵은 KAIST 도서관과 관계가 없어요',
  yearLine: (book) => (book.yearBook ? `${book.yearBook}년 KAIST 사서추천` : null),
}

export const PICKS_LISTS: Record<PicksListKey, PicksList> = {
  librarian: LIBRARIAN,
  leedongjin: LEEDONGJIN,
  snu100: SNU100,
  kaist: KAIST,
}

/**
 * The tabs in screen order (user 2026-10-19: "사서 추천을 맨 앞에 그 다음에 카이스추천 서울대 추천"). Lee Dong-jin's list is
 * off the tabs ("이동진 추천 빼고"); its address still opens it.
 */
export const PICKS_TABS: readonly PicksList[] = [LIBRARIAN, KAIST, SNU100]

/** The list an address names, or null for any other name. */
export function picksList(key: string | undefined): PicksList | null {
  return key === 'librarian' || key === 'leedongjin' || key === 'snu100' || key === 'kaist' ? PICKS_LISTS[key] : null
}

/** [영상 보기] (R6-2): the video's YouTube page, built from the id only; null when the id has another shape. */
export function videoUrl(videoId: string | null): string | null {
  return videoId && VIDEO_ID.test(videoId) ? `https://www.youtube.com/watch?v=${videoId}` : null
}
