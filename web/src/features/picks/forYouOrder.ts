import type { ForYouCard } from '../../api/types'

/** [확정] R6-3: personal picks come 20 at a time, 60 at most. */
export const PAGE_SIZE = 20
export const MAX_PERSONAL = 60
/** [확정] R6-1: at most two books of one author in a row (AC-8). */
export const SAME_AUTHOR_RUN = 2
/** [확정] R6-1b: in each 20, books shown again at about the 8th and 16th places (AC-17). */
export const RESHOW_PLACES = [8, 16] as const

export interface ForYouLayout {
  /** the one wide card at the top: the first personal pick, else the first base book (R6-2) */
  top: ForYouCard | null
  /** the personal picks after `top` that are shown now */
  personal: ForYouCard[]
  /** "비슷한 책 더 보기" has another 20 */
  hasMore: boolean
  /** the base list after the personal picks, without books already shown above */
  base: ForYouCard[]
}

/**
 * The browser's part of the personal picks (decision #4): drop seen books, no three of one author in a row, 20 at a
 * time with up to two seen books mixed in at the 8th and 16th places of each 20, the base list after them with books
 * already shown above left out (R6-2).
 * @param pages how many 20s are open ("비슷한 책 더 보기" adds one)
 */
export function layOut(
  base: ForYouCard[],
  similar: ForYouCard[],
  reshow: ForYouCard[],
  seen: ReadonlySet<number>,
  pages: number,
): ForYouLayout {
  const fresh = limitAuthorRuns(similar.filter((card) => !seen.has(card.bookId))).slice(0, MAX_PERSONAL)
  const chunks: ForYouCard[][] = []
  const shownAgain = reshow.filter((card) => !fresh.some((other) => other.bookId === card.bookId))
  let next = 0
  for (let start = 0; start < fresh.length; start += PAGE_SIZE) {
    const chunk = fresh.slice(start, start + PAGE_SIZE)
    for (const place of RESHOW_PLACES) {
      if (next < shownAgain.length && chunk.length >= place - 1) {
        chunk.splice(place - 1, 0, shownAgain[next++])
      }
    }
    chunks.push(chunk)
  }
  const open = chunks.slice(0, Math.max(1, pages)).flat()
  const inPersonal = new Set(chunks.flat().map((card) => card.bookId))
  const rest = base.filter((card) => !inPersonal.has(card.bookId))
  if (open.length > 0) {
    return { top: open[0], personal: open.slice(1), hasMore: chunks.length > Math.max(1, pages), base: rest }
  }
  return { top: rest[0] ?? null, personal: [], hasMore: false, base: rest.slice(1) }
}

/**
 * Keeps the order but moves a book back when it would be the third of its author in a row; books without an author
 * key never count as one author.
 */
export function limitAuthorRuns(cards: ForYouCard[]): ForYouCard[] {
  const waiting = [...cards]
  const placed: ForYouCard[] = []
  while (waiting.length > 0) {
    const index = waiting.findIndex((card) => !wouldRun(placed, card))
    // only one author is left: the rest goes in as it is
    const take = index === -1 ? 0 : index
    placed.push(waiting.splice(take, 1)[0])
  }
  return placed
}

function wouldRun(placed: ForYouCard[], card: ForYouCard): boolean {
  if (!card.authorKey || placed.length < SAME_AUTHOR_RUN) return false
  return placed.slice(-SAME_AUTHOR_RUN).every((other) => other.authorKey === card.authorKey)
}
