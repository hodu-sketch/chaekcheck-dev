import type { ResultFilter } from '../../api/types'

/**
 * The result filter in the address. "지금 빌릴 수 있는 책만" is the default and leaves no parameter; only "전체"
 * writes `filter=all` (user request 2026-10-05; "전체" was the default before).
 */
export function readFilter(params: URLSearchParams): ResultFilter {
  return params.get('filter') === 'all' ? 'all' : 'available'
}

/** Sets or removes `filter` on `params` and returns it. */
export function writeFilter(filter: ResultFilter, params: URLSearchParams): URLSearchParams {
  if (filter === 'all') params.set('filter', 'all')
  else params.delete('filter')
  return params
}
