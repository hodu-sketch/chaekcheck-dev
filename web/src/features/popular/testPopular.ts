import type { PopularBook, PopularBooks, PopularCategory } from '../../api/types'

/** Category names in the order of the 202609 sales (AC-4). */
export const CATEGORY_NAMES = [
  '전체',
  '소설',
  '경제·경영',
  '자기계발',
  '문학·인물',
  '사회',
  '철학·종교',
  '역사',
  '언어',
  '예술',
  '과학',
  '컴퓨터·IT',
  '생활·취미',
  '기술·공학',
  '법',
  '참고·정보',
  '지리·환경',
]

/** Fifty books: odd ranks have an e-book in my libraries, even ones not; rank 3 has no cover. */
export function testBooks(prefix: string, count = 50): PopularBook[] {
  return Array.from({ length: count }, (_, index) => {
    const rank = index + 1
    const available = rank % 2 === 1
    return {
      rank,
      title: `${prefix} ${rank}`,
      coverUrl: rank === 3 ? null : `/api/v1/popular-books/covers/${1000 + rank}`,
      bookId: available ? 5000 + rank : null,
      available,
    }
  })
}

export function testCategories(): PopularCategory[] {
  return CATEGORY_NAMES.map((name, index) => ({
    code: index === 0 ? 'ALL' : String(index).padStart(20, '0'),
    name,
    books: testBooks(name),
  }))
}

export function testPopular(overrides: Partial<PopularBooks> = {}): PopularBooks {
  return { month: '2026-09', fetchedAt: '2026-10-03T05:01:32+09:00', categories: testCategories(), ...overrides }
}
