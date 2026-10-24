import { describe, expect, it } from 'vitest'
import type { ForYouCard } from '../../api/types'
import { layOut, limitAuthorRuns } from './forYouOrder'

function card(bookId: number, authorKey: string | null = `a${bookId}`): ForYouCard {
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
    authorKey,
  }
}

const ids = (cards: ForYouCard[]) => cards.map((c) => c.bookId)

describe('forYouOrder (cycle 23 decision #4, R6)', () => {
  it('drops seen books and puts the first personal pick on top', () => {
    const out = layOut([card(1), card(2)], [card(10), card(11), card(12)], [], new Set([11]), 1)

    expect(out.top?.bookId).toBe(10)
    expect(ids(out.personal)).toEqual([12])
    expect(ids(out.base)).toEqual([1, 2])
  })

  it('without personal picks the first base book is on top', () => {
    const out = layOut([card(1), card(2), card(3)], [], [card(50)], new Set(), 1)

    expect(out.top?.bookId).toBe(1)
    expect(ids(out.personal)).toEqual([])
    expect(ids(out.base)).toEqual([2, 3])
    expect(out.hasMore).toBe(false)
  })

  it('never shows three books of one author in a row (AC-8)', () => {
    const cards = [card(1, 'x'), card(2, 'x'), card(3, 'x'), card(4, 'y'), card(5, 'x'), card(6, null), card(7, null)]

    const ordered = limitAuthorRuns(cards)

    expect(ids(ordered)).toEqual([1, 2, 4, 3, 5, 6, 7])
    for (let i = 2; i < ordered.length; i++) {
      const run = ordered.slice(i - 2, i + 1).map((c) => c.authorKey)
      expect(run[0] !== null && run.every((key) => key === run[0])).toBe(false)
    }
  })

  it('mixes at most two seen books into each 20 at the 8th and 16th places (AC-17)', () => {
    const similar = Array.from({ length: 45 }, (_, i) => card(100 + i))
    const reshow = [card(1), card(2), card(3), card(4), card(5)]

    const first = layOut([], similar, reshow, new Set(), 1)
    const all = [first.top!, ...first.personal]
    expect(all).toHaveLength(22)
    expect(all[7].bookId).toBe(1)
    expect(all[15].bookId).toBe(2)
    expect(first.hasMore).toBe(true)

    const three = layOut([], similar, reshow, new Set(), 3)
    const shown = [three.top!, ...three.personal]
    expect(shown[22 + 7].bookId).toBe(3)
    expect(shown[22 + 15].bookId).toBe(4)
    // the last 5 books: no 8th place, so no book shown again there
    expect(shown.slice(44)).toHaveLength(5)
    expect(three.hasMore).toBe(false)
    for (let start = 0; start < shown.length; start += 22) {
      expect(shown.slice(start, start + 22).filter((c) => c.bookId < 100).length).toBeLessThanOrEqual(2)
    }
  })

  it('a base book that is also a personal pick stays with the personal picks only', () => {
    const out = layOut([card(1), card(10), card(2)], [card(10), card(11)], [], new Set(), 1)

    expect(ids([out.top!, ...out.personal])).toEqual([10, 11])
    expect(ids(out.base)).toEqual([1, 2])
  })

  it('60 personal picks at most', () => {
    const similar = Array.from({ length: 70 }, (_, i) => card(100 + i))
    const out = layOut([], similar, [], new Set(), 5)
    expect(out.personal.length + 1).toBe(60)
  })
})
