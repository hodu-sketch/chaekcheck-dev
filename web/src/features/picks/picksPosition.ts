/**
 * The key of a list's place in `history.state`, next to the router's own state; one per list (cycle 21 spec R2-6,
 * C14 AC-20).
 */
function stateKey(list: string): string {
  return `chaekcheckPicks:${list}`
}

/** Where the picks screen was: the chosen category chip, how many books are shown, and the scroll position (AC-20). */
export interface PicksPosition {
  /** The chosen category's code; '' = the list's first one (전체). */
  category: string
  /** How many books of that category are shown (grows by "더 보기"). */
  shown: number
  scrollY: number
}

/**
 * [안] method (cycle 10 step 7-5): the place is kept in this history entry with `history.replaceState`, so back from
 * the book screen (S3) finds it; nothing is kept in the address or in storage, and the screen does not render again
 * on every scroll (a router `navigate(…, { replace, state })` would).
 */
export function readPosition(list: string): PicksPosition | null {
  const state: unknown = window.history.state
  if (typeof state !== 'object' || state === null) return null
  const saved = (state as Record<string, unknown>)[stateKey(list)]
  if (typeof saved !== 'object' || saved === null) return null
  const { category, shown, scrollY } = saved as Partial<PicksPosition>
  return {
    category: typeof category === 'string' ? category : '',
    shown: typeof shown === 'number' && shown > 0 ? shown : 0,
    scrollY: typeof scrollY === 'number' ? scrollY : 0,
  }
}

export function savePosition(list: string, change: Partial<PicksPosition>): void {
  const state: unknown = window.history.state
  const current = typeof state === 'object' && state !== null ? (state as Record<string, unknown>) : {}
  const saved = readPosition(list) ?? { category: '', shown: 0, scrollY: 0 }
  window.history.replaceState({ ...current, [stateKey(list)]: { ...saved, ...change } }, '')
}
