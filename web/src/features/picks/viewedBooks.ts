/**
 * Books this browser looked at, for 「나에게 맞는 책」 (cycle 23 spec R5, R6-1b, R6-4). Kept in this browser only
 * (`localStorage`); the server never gets the record, only the base books and the books to show again (R5-1, C4).
 *
 * Points per book (R5-2), each counted once however often it happens: opened 1, kept open 10 seconds while visible 2,
 * a library pressed there 3. Records older than 30 days are dropped, and past 200 books the oldest go first (R5-5).
 */

/** [안] storage key. */
export const VIEWED_BOOKS_KEY = 'chaekcheck.viewedBooks.v1'

const DAY = 24 * 60 * 60 * 1000

/** [확정] R5-5 */
export const KEEP_DAYS = 30
/** [확정] R5-5 */
export const MAX_BOOKS = 200
/** [확정] R5-5: base books */
export const BASE_COUNT = 3
/** [확정] R6-1b: a seen book may come back after this many days */
export const RESHOW_AFTER_DAYS = 3
/** [확정] design-delta API: at most 6 books to show again are sent */
export const RESHOW_COUNT = 6
/** [확정] R6-4: [빼기] keeps a book out of the base books this long */
export const REMOVE_DAYS = 30
/** [확정] R6-4: S19 lists this many */
export const DIALOG_COUNT = 10

export interface ViewedBook {
  bookId: number
  title: string
  opened: boolean
  /** kept open 10 seconds while visible */
  dwelled: boolean
  libraryClicked: boolean
  /** last time anything happened with the book (ms since epoch) */
  lastAt: number
  /** until then the book is not a base book (S19 [빼기]); null when never removed */
  removedUntil: number | null
}

export function points(book: ViewedBook): number {
  return (book.opened ? 1 : 0) + (book.dwelled ? 2 : 0) + (book.libraryClicked ? 3 : 0)
}

/** The record as stored, old entries dropped; anything broken reads as an empty record. */
export function readViewed(now: number = Date.now()): ViewedBook[] {
  let raw: string | null = null
  try {
    raw = localStorage.getItem(VIEWED_BOOKS_KEY)
  } catch {
    return []
  }
  if (!raw) return []
  try {
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    // a time more than a day ahead of this clock (a clock that was set wrong) counts as now, so it still ages out
    const books = parsed
      .filter(isViewedBook)
      .map((book) => (book.lastAt > now + DAY ? { ...book, lastAt: now } : book))
    return trim(books, now)
  } catch {
    return []
  }
}

function writeViewed(books: ViewedBook[]): void {
  try {
    localStorage.setItem(VIEWED_BOOKS_KEY, JSON.stringify(books))
  } catch {
    // private windows or full storage: the tab just shows the base list
  }
}

function isViewedBook(value: unknown): value is ViewedBook {
  if (typeof value !== 'object' || value === null) return false
  const book = value as Record<string, unknown>
  return (
    Number.isSafeInteger(book.bookId) &&
    (book.bookId as number) > 0 &&
    typeof book.title === 'string' &&
    typeof book.opened === 'boolean' &&
    typeof book.dwelled === 'boolean' &&
    typeof book.libraryClicked === 'boolean' &&
    typeof book.lastAt === 'number' &&
    Number.isFinite(book.lastAt) &&
    (book.removedUntil === null || (typeof book.removedUntil === 'number' && Number.isFinite(book.removedUntil)))
  )
}

/** 30 days, then the 200 most recent. */
function trim(books: ViewedBook[], now: number): ViewedBook[] {
  const kept = books.filter((book) => book.lastAt > now - KEEP_DAYS * DAY)
  if (kept.length <= MAX_BOOKS) return kept
  return [...kept].sort((a, b) => b.lastAt - a.lastAt).slice(0, MAX_BOOKS)
}

type Mark = 'opened' | 'dwelled' | 'libraryClicked'

function mark(bookId: number, title: string | null, what: Mark, now: number): void {
  const books = readViewed(now)
  const found = books.find((book) => book.bookId === bookId)
  if (found) {
    found[what] = true
    found.lastAt = now
    if (title) found.title = title
  } else if (title) {
    // a library press without an opened book first (should not happen) adds nothing: every record has its title
    books.push({
      bookId,
      title: title ?? '',
      opened: what === 'opened',
      dwelled: what === 'dwelled',
      libraryClicked: what === 'libraryClicked',
      lastAt: now,
      removedUntil: null,
    })
  }
  writeViewed(trim(books, now))
}

/** The book screen or a picks book window opened the book (R5-3). */
export function recordOpened(bookId: number, title: string, now: number = Date.now()): void {
  mark(bookId, title, 'opened', now)
}

/** The book screen or window stayed visible 10 seconds (R5-4). */
export function recordDwelled(bookId: number, title: string, now: number = Date.now()): void {
  mark(bookId, title, 'dwelled', now)
}

/** A library line was pressed on the book screen or in a picks book window (R5-3). */
export function recordLibraryClicked(bookId: number, now: number = Date.now()): void {
  mark(bookId, null, 'libraryClicked', now)
}

function removed(book: ViewedBook, now: number): boolean {
  return book.removedUntil !== null && book.removedUntil > now
}

/** Most points first, the more recent first on equal points. */
function byPoints(a: ViewedBook, b: ViewedBook): number {
  return points(b) - points(a) || b.lastAt - a.lastAt
}

/** The books S19 lists: not removed, by points, at most 10 (R6-4). */
export function recentBooks(books: ViewedBook[], now: number = Date.now()): ViewedBook[] {
  return books.filter((book) => !removed(book, now)).sort(byPoints).slice(0, DIALOG_COUNT)
}

/** The base books: the top 3 by points, the more recent first on equal points, removed ones left out (R5-5). */
export function baseBooks(books: ViewedBook[], now: number = Date.now()): ViewedBook[] {
  return recentBooks(books, now).slice(0, BASE_COUNT)
}

/**
 * Books that may be shown again (R6-1b): seen more than 3 days ago, no library pressed, not a base book, not removed;
 * at most 6, by points.
 */
export function reshowBooks(books: ViewedBook[], now: number = Date.now()): ViewedBook[] {
  const base = new Set(baseBooks(books, now).map((book) => book.bookId))
  return books
    .filter(
      (book) =>
        book.lastAt < now - RESHOW_AFTER_DAYS * DAY &&
        !book.libraryClicked &&
        !base.has(book.bookId) &&
        !removed(book, now),
    )
    .sort(byPoints)
    .slice(0, RESHOW_COUNT)
}

/** S19 [빼기]: the book is no base book for 30 days. */
export function removeFromBase(bookId: number, now: number = Date.now()): void {
  const books = readViewed(now)
  const found = books.find((book) => book.bookId === bookId)
  if (!found) return
  found.removedUntil = now + REMOVE_DAYS * DAY
  writeViewed(books)
}

/** S19 "최근 본 책 기록 모두 지우기". */
export function clearViewed(): void {
  try {
    localStorage.removeItem(VIEWED_BOOKS_KEY)
  } catch {
    // nothing stored
  }
}
