import type { BookRole, BookSort, SearchField } from '../../api/types'

/** Order of the fields, and the order that picks the one condition of the direct site search [확정]. */
export const SEARCH_FIELDS: readonly SearchField[] = ['title', 'author', 'publisher']

export const FIELD_LABELS: Record<SearchField, string> = { title: '제목', author: '저자', publisher: '출판사' }

export const BOOK_ROLES: readonly BookRole[] = ['translator', 'illustrator', 'editor']

export const ROLE_LABELS: Record<BookRole, string> = { translator: '옮긴이', illustrator: '그림', editor: '엮은이' }

export const SORTS: readonly BookSort[] = ['default', 'newest', 'oldest']

export const SORT_LABELS: Record<BookSort, string> = {
  default: '기본 순서',
  newest: '출판일 최신순',
  oldest: '출판일 오래된순',
}

/** One condition for the direct site search (cycle 03 APIs, ADR-0025). */
export interface SearchCondition {
  field: SearchField
  text: string
}

/**
 * The catalog search in the address (ADR-0033): every filled field, and per field whether it must match exactly.
 * Texts are as in the address (not normalized). Search conditions are never stored in the browser [확정].
 */
export interface BookSearchInput {
  title: string
  author: string
  publisher: string
  exact: SearchField[]
  roles: BookRole[]
  sort: BookSort
}

export const EMPTY_INPUT: BookSearchInput = { title: '', author: '', publisher: '', exact: [], roles: [], sort: 'default' }

/**
 * Reads every condition of the address (ADR-0033). `q` is not read (ADR-0025). Null means the start screen (no
 * field filled). Unknown values in `exact`, `roles` and `sort` are dropped.
 */
export function readBookSearch(params: URLSearchParams): BookSearchInput | null {
  const input: BookSearchInput = {
    title: params.get('title') ?? '',
    author: params.get('author') ?? '',
    publisher: params.get('publisher') ?? '',
    exact: listOf(params.get('exact'), SEARCH_FIELDS),
    roles: listOf(params.get('roles'), BOOK_ROLES),
    sort: (SORTS as readonly string[]).includes(params.get('sort') ?? '') ? (params.get('sort') as BookSort) : 'default',
  }
  return filledFields(input).length === 0 ? null : input
}

/** Fields with text, in title → author → publisher order. */
export function filledFields(input: BookSearchInput): SearchField[] {
  return SEARCH_FIELDS.filter((field) => input[field].trim() !== '')
}

/**
 * The one condition of the direct site search: the first filled field, title → author → publisher [안] (spec 3.7).
 * Null means the start screen.
 */
export function readCondition(params: URLSearchParams): SearchCondition | null {
  for (const field of SEARCH_FIELDS) {
    const text = params.get(field)
    if (text) return { field, text }
  }
  return null
}

/** Address parameters of a catalog search; empty fields, unused `exact` and author-less `roles` are left out. */
export function writeBookSearch(input: BookSearchInput, into: URLSearchParams = new URLSearchParams()): URLSearchParams {
  for (const field of SEARCH_FIELDS) {
    if (input[field].trim()) into.set(field, input[field])
    else into.delete(field)
  }
  const exact = input.exact.filter((field) => input[field].trim())
  if (exact.length) into.set('exact', exact.join(','))
  else into.delete('exact')
  if (input.author.trim() && input.roles.length) into.set('roles', input.roles.join(','))
  else into.delete('roles')
  if (input.sort !== 'default') into.set('sort', input.sort)
  else into.delete('sort')
  return into
}

function listOf<T extends string>(raw: string | null, allowed: readonly T[]): T[] {
  if (!raw) return []
  const values = raw.split(',').map((value) => value.trim())
  return allowed.filter((value) => values.includes(value))
}
