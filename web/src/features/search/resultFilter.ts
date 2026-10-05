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
