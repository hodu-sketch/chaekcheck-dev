
/** The words of 「책첵 추천」 (first 「나에게 맞는 책」), all [확정] (cycle 23 handoff 문구표). */
export const FOR_YOU_TEXT = {
  changeBase: '기준 책 바꾸기',
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
