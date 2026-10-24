import { beforeEach, describe, expect, it } from 'vitest'
import {
  baseBooks,
  clearViewed,
  points,
  readViewed,
  recentBooks,
  recordDwelled,
  recordLibraryClicked,
  recordOpened,
  removeFromBase,
  reshowBooks,
  VIEWED_BOOKS_KEY,
} from './viewedBooks'

const DAY = 24 * 60 * 60 * 1000
const NOW = Date.parse('2026-10-26T10:00:00+09:00')

describe('viewedBooks (cycle 23 R5, R6-1b, R6-4)', () => {
  beforeEach(() => localStorage.clear())

  it('counts each kind of point once however often it happens', () => {
    recordOpened(1, '책 A', NOW)
    recordOpened(1, '책 A', NOW + 1000)
    recordDwelled(1, '책 A', NOW + 2000)
    recordDwelled(1, '책 A', NOW + 3000)
    recordLibraryClicked(1, NOW + 4000)
    recordLibraryClicked(1, NOW + 5000)

    const [book] = readViewed(NOW + 6000)
    expect(points(book)).toBe(6)
    expect(book.lastAt).toBe(NOW + 5000)
  })

  it('drops records older than 30 days and keeps the 200 most recent', () => {
    recordOpened(1, 'old', NOW - 31 * DAY)
    for (let i = 0; i < 201; i++) recordOpened(100 + i, `책 ${i}`, NOW - DAY + i)

    const books = readViewed(NOW)
    expect(books).toHaveLength(200)
    expect(books.some((book) => book.bookId === 1)).toBe(false)
    expect(books.some((book) => book.bookId === 100)).toBe(false)
  })

  it('picks the three base books by points, the more recent first on equal points', () => {
    recordOpened(1, 'A', NOW - 5000)
    recordOpened(2, 'B', NOW - 4000)
    recordOpened(3, 'C', NOW - 3000)
    recordOpened(4, 'D', NOW - 2000)
    recordDwelled(1, 'A', NOW - 6000)

    expect(baseBooks(readViewed(NOW), NOW).map((book) => book.bookId)).toEqual([1, 4, 3])
  })

  it('[빼기] keeps a book out of the base books for 30 days', () => {
    recordOpened(1, 'A', NOW)
    recordLibraryClicked(1, NOW)
    recordOpened(2, 'B', NOW)
    recordOpened(3, 'C', NOW)
    recordOpened(4, 'D', NOW)

    removeFromBase(1, NOW)
    expect(baseBooks(readViewed(NOW), NOW).map((book) => book.bookId)).not.toContain(1)
    expect(baseBooks(readViewed(NOW + 29 * DAY), NOW + 29 * DAY)).toHaveLength(3)
    expect(recentBooks(readViewed(NOW), NOW).map((book) => book.bookId)).not.toContain(1)
    // seen again later: once the 30 days are over it is a base book again
    recordOpened(1, 'A', NOW + 20 * DAY)
    expect(baseBooks(readViewed(NOW + 30 * DAY + 1), NOW + 30 * DAY + 1).map((book) => book.bookId)).toContain(1)
  })

  it('shows a book again only after 3 days, without a library pressed, not as a base book, not removed', () => {
    // in time order, as a browser records them
    recordOpened(13, 'removed', NOW - 6 * DAY)
    removeFromBase(13, NOW - 6 * DAY + 1)
    recordOpened(12, 'pressed', NOW - 5 * DAY)
    recordLibraryClicked(12, NOW - 5 * DAY)
    recordOpened(10, 'old enough', NOW - 4 * DAY)
    recordOpened(11, 'too new', NOW - 2 * DAY)
    for (let id = 1; id <= 3; id++) {
      recordOpened(id, `pressed ${id}`, NOW)
      recordLibraryClicked(id, NOW)
    }

    // 12 is a base book by points too, so the conditions are checked with the three pressed books on top
    expect(reshowBooks(readViewed(NOW), NOW).map((book) => book.bookId)).toEqual([10])
  })

  it('a library press without an opened book adds nothing; a time set far ahead counts as now', () => {
    recordLibraryClicked(9, NOW)
    expect(readViewed(NOW)).toEqual([])
    localStorage.setItem(
      VIEWED_BOOKS_KEY,
      JSON.stringify([{ bookId: 1, title: 'A', opened: true, dwelled: false, libraryClicked: false, lastAt: NOW + 400 * DAY, removedUntil: null }]),
    )
    expect(readViewed(NOW)[0].lastAt).toBe(NOW)
  })

  it('reads a broken record as empty and clears everything', () => {
    localStorage.setItem(VIEWED_BOOKS_KEY, '{nope')
    expect(readViewed(NOW)).toEqual([])
    localStorage.setItem(VIEWED_BOOKS_KEY, JSON.stringify([{ bookId: 'x' }, 5, null]))
    expect(readViewed(NOW)).toEqual([])

    recordOpened(1, 'A', NOW)
    clearViewed()
    expect(readViewed(NOW)).toEqual([])
  })
})
