import type { ForYouCard } from '../../api/types'

/** [확정] R6-3: personal picks come 20 at a time, 60 at most. */
export const PAGE_SIZE = 20
export const MAX_PERSONAL = 60
/** [확정] R6-1: at most two books of one author in a row (AC-8). */
export const SAME_AUTHOR_RUN = 2
/** [확정] R6-1b: in each 20, books shown again at about the 8th and 16th places (AC-17). */
export const RESHOW_PLACES = [8, 16] as const
/**
 * While this browser has seen fewer books than this, the base list and the personal picks take the field groups in
 * turn (user 2026-10-25: "처음에 본 책들이 많아지지 않은 이상 처음에는 다양한 분야의 책을 골고루 섞어줘", 3 [안] approved).
 */
export const MIX_FIELDS_BELOW_SEEN = 3

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
 * already shown above left out (R6-2). With few seen books both lists take the field groups in turn first.
 * @param pages how many 20s are open ("비슷한 책 더 보기" adds one)
 */
export function layOut(
  base: ForYouCard[],
  similar: ForYouCard[],
  reshow: ForYouCard[],
  seen: ReadonlySet<number>,
  pages: number,
): ForYouLayout {
  const mix = seen.size < MIX_FIELDS_BELOW_SEEN
  const unseen = similar.filter((card) => !seen.has(card.bookId))
  const fresh = limitAuthorRuns(mix ? mixFields(unseen) : unseen).slice(0, MAX_PERSONAL)
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
  const left = base.filter((card) => !inPersonal.has(card.bookId))
  const rest = mix ? mixFields(left) : left
  if (open.length > 0) {
    return { top: open[0], personal: open.slice(1), hasMore: chunks.length > Math.max(1, pages), base: rest }
  }
  return { top: rest[0] ?? null, personal: [], hasMore: false, base: rest.slice(1) }
}

/**
 * One book of each field group in turn, each group in its own order and the groups in the order their first book came
 * (so the first book stays first); books without a group count as one group, other.
 */
export function mixFields(cards: ForYouCard[]): ForYouCard[] {
  const groups = new Map<string, ForYouCard[]>()
  for (const card of cards) {
    const group = card.fieldGroup ?? 'other'
    const list = groups.get(group)
    if (list) list.push(card)
    else groups.set(group, [card])
  }
  const queues = [...groups.values()]
  const mixed: ForYouCard[] = []
  for (let i = 0; mixed.length < cards.length; i++) {
    for (const queue of queues) {
      if (i < queue.length) mixed.push(queue[i])
    }
  }
  return mixed
}

/**
 * Once in a while a book of a field this browser has not looked at (user 2026-10-25: "자신이 읽던 것과 아닌 것들도 가끔
 * 들어가야 해 아주 가끔"): from the 10th place on, one such book every this many places [안].
 */
export const SURPRISE_EVERY = 15
export const SURPRISE_FIRST = 10

/**
 * Moves up, at the 10th place and then every 15th, the next book whose field group this browser never looked at; only
 * once the field mix has stopped (three or more seen books) and only books with a known group count. The order of the
 * rest stays; nothing is added or dropped.
 */
export function withSurprises(cards: ForYouCard[], lookedGroups: ReadonlySet<string>, seenCount: number): ForYouCard[] {
  if (seenCount < MIX_FIELDS_BELOW_SEEN || lookedGroups.size === 0) return cards
  const out = [...cards]
  for (let place = SURPRISE_FIRST - 1; place < out.length; place += SURPRISE_EVERY) {
    const from = out.findIndex(
      (card, index) => index >= place && card.fieldGroup != null && !lookedGroups.has(card.fieldGroup),
    )
    if (from === -1) break
    if (from > place) out.splice(place, 0, out.splice(from, 1)[0])
  }
  return out
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
