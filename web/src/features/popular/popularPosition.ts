/** The key of the popular screen's place in `history.state`, next to the router's own state. */
const KEY = 'chaekcheckPopular'

/** Where the popular screen was: each category's page (0 = ranks 1-9) and the scroll position (AC-20). */
export interface PopularPosition {
  pages: Record<string, number>
  scrollY: number
}

/**
 * [안] method (cycle 10 step 7-5): the place is kept in this history entry with `history.replaceState`, so back from
 * the book screen (S3) finds it; nothing is kept in the address or in storage, and the screen does not render again
 * on every scroll (a router `navigate(…, { replace, state })` would).
 */
export function readPosition(): PopularPosition | null {
  const state: unknown = window.history.state
  if (typeof state !== 'object' || state === null) return null
  const saved = (state as Record<string, unknown>)[KEY]
  if (typeof saved !== 'object' || saved === null) return null
  const { pages, scrollY } = saved as Partial<PopularPosition>
  return {
    pages: typeof pages === 'object' && pages !== null ? pages : {},
    scrollY: typeof scrollY === 'number' ? scrollY : 0,
  }
}

export function savePosition(change: Partial<PopularPosition>): void {
  const state: unknown = window.history.state
  const current = typeof state === 'object' && state !== null ? (state as Record<string, unknown>) : {}
  const saved = readPosition() ?? { pages: {}, scrollY: 0 }
  window.history.replaceState({ ...current, [KEY]: { ...saved, ...change } }, '')
}
