import { describe, expect, it } from 'vitest'
import type { ForYouCard } from '../../api/types'
import { cardsFor, chipsFor } from './forYouChips'
import { withSurprises } from './forYouOrder'
import type { ViewedBook } from './viewedBooks'

function card(bookId: number, fieldGroup: string | null): ForYouCard {
  return {
    bookId,
    title: `책 ${bookId}`,
    author: null,
    publisher: null,
    coverUrl: null,
    blurb: null,
    tags: [],
    planLibraryCount: 1,
    lists: [],
    authorKey: null,
    fieldGroup,
  }
}

function seen(fieldGroup: string | null): ViewedBook {
  return { bookId: 1, title: 't', opened: true, dwelled: false, libraryClicked: false, lastAt: 0, removedUntil: null, fieldGroup }
}

describe('책첵 추천 chips (user 2026-10-25)', () => {
  const cards = [card(1, 'literature'), card(2, 'science'), card(3, null), card(4, 'arts')]

  it('shows only chips with books, 최근 본 책과 비슷한 only with personal picks', () => {
    expect(chipsFor(cards, new Set(), []).map((c) => c.name)).toEqual(['전체', '소설·에세이', '과학·기술', '예술·취미'])
    expect(chipsFor(cards, new Set([3]), []).map((c) => c.code)).toEqual(['all', 'similar', 'literature', 'science', 'arts'])
  })

  it('puts the fields this person looked at most first', () => {
    const viewed = [seen('arts'), seen('arts'), seen('science')]
    expect(chipsFor(cards, new Set(), viewed).map((c) => c.code)).toEqual(['all', 'arts', 'science', 'literature'])
  })

  it('a chip shows its books', () => {
    expect(cardsFor('science', cards, new Set()).map((c) => c.bookId)).toEqual([2])
    expect(cardsFor('similar', cards, new Set([3, 4])).map((c) => c.bookId)).toEqual([3, 4])
    expect(cardsFor('all', cards, new Set())).toHaveLength(4)
  })
})

describe('now and then a field never looked at (user 2026-10-25)', () => {
  it('moves one such book up at the 10th place and then every 15th, once three books are seen', () => {
    const list = [...Array.from({ length: 30 }, (_, i) => card(i + 1, 'literature')), card(99, 'science'), card(98, 'arts')]
    const out = withSurprises(list, new Set(['literature']), 5)
    expect(out[9].bookId).toBe(99)
    expect(out[24].bookId).toBe(98)
    expect(out).toHaveLength(32)
    // below three seen books the field mix does the work instead
    expect(withSurprises(list, new Set(['literature']), 2)).toBe(list)
  })
})
