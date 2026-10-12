import type { PickBook, PickCategory, Picks } from '../../api/types'

/** Field names in display order, then one new field (AC-4, AC-4b). */
export const CATEGORIES = [
  { code: 'ALL', name: '전체' },
  { code: '11', name: '문학' },
  { code: '6', name: '인문' },
  { code: '5', name: '사회' },
  { code: '4', name: '자연' },
  { code: '425', name: '테마' },
  { code: '999', name: '새분야' },
]

/** Ten books: odd ones have an e-book in my libraries, even ones not; the third has no cover. */
export function testBooks(prefix: string, category: string, count = 10): PickBook[] {
  return Array.from({ length: count }, (_, index) => {
    const n = index + 1
    const available = n % 2 === 1
    return {
      recomNo: `2026092216313582${String(9100 - n).padStart(4, '0')}`,
      category,
      recommendedMonth: n <= 5 ? '2026-10' : '2026-08',
      title: `${prefix} ${n}`,
      coverUrl: n === 3 ? null : `/api/v1/picks/covers/${1000 + n}`,
      bookId: available ? 5000 + n : null,
      available,
    }
  })
}

export function testCategories(): PickCategory[] {
  return CATEGORIES.map(({ code, name }) => ({
    code,
    name,
    books: testBooks(name, code === 'ALL' ? '11' : code),
  }))
}

export function testPicks(overrides: Partial<Picks> = {}): Picks {
  return { fetchedAt: '2026-10-12T05:00:41+09:00', categories: testCategories(), ...overrides }
}
