import type { ResultFilter } from '../../api/types'

/**
 * The result filter in the address. "지금 빌릴 수 있는 책만" is the default and leaves no parameter; only "전체"
 * writes `filter=all` (user request 2026-10-05; "전체" was the default before). A missing value reads the choice kept
 * in this browser (`fallback`, user request 2026-10-05); `filter=available` in the address wins over it.
 */
export function readFilter(params: URLSearchParams, fallback: ResultFilter = 'available'): ResultFilter {
  const value = params.get('filter')
  return value === 'all' ? 'all' : value === 'available' ? 'available' : fallback
}

/** Sets or removes `filter` on `params` and returns it. */
export function writeFilter(filter: ResultFilter, params: URLSearchParams): URLSearchParams {
  if (filter === 'all') params.set('filter', 'all')
  else params.delete('filter')
  return params
}

/** "N곳은 확인하지 못했어요" (cycle 20 handoff 1-13 [확정]): the libraries whose lookup failed, counted apart. */
export function uncheckedText(libraries: number): string {
  return `${libraries}곳은 확인하지 못했어요`
}

/**
 * The empty text of a batch (cycle 20 handoff 1-13): "지금까지 N곳에서 … 찾지 못했습니다" counts only the libraries that
 * answered (`answered` minus `unchecked`), and the failed ones get their own sentence after it.
 */
export function emptyResultText(answered: number, unchecked: number, filter: ResultFilter): string {
  const counted = Math.max(0, answered - unchecked)
  const what = filter === 'available' ? '지금 빌릴 수 있는 책을' : '이 제목을'
  const main = counted > 0 ? `지금까지 ${counted}곳에서 ${what} 찾지 못했습니다` : ''
  if (unchecked === 0) return main
  return main ? `${main} · ${uncheckedText(unchecked)}` : uncheckedText(unchecked)
}
