import type { LibraryScope } from '../../api/types'

/**
 * Which libraries a search uses (cycle 22 R4-1, D10, ADR-0135): there is no switch any more. Always `joinable`
 * (사는 곳·일하는 곳으로 가입할 수 있는 곳 + 어디 살든 가입되는 곳 + 직접 추가), except `scope=all` in the address, which only
 * that one search carries (the 전국 links and the 상세검색 box). Nothing is read from or kept in this browser.
 */
export function readScope(params: URLSearchParams): LibraryScope {
  return params.get('scope') === 'all' ? 'all' : 'joinable'
}
