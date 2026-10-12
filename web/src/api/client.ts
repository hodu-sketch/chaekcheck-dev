import type {
  BookDetail,
  BookPage,
  BookRole,
  BookSort,
  LatestJoinSurvey,
  LibraryEligibilityRow,
  LibraryPage,
  LibraryScope,
  Picks,
  Problem,
  RegionResolution,
  SearchField,
  SidoNode,
  VisitFilter,
} from './types'

/** A non-2xx API response. `problem.code` says what went wrong. */
export class ApiError extends Error {
  readonly status: number
  readonly problem: Problem

  constructor(status: number, problem: Problem) {
    super(problem.detail ?? problem.title)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

/** Reads an error body as Problem Details; falls back to a generic problem when the body is not JSON. */
export async function readProblem(response: Response): Promise<ApiError> {
  try {
    const problem = (await response.json()) as Problem
    if (problem && typeof problem.code === 'string') return new ApiError(response.status, problem)
  } catch {
    // not JSON (for example a proxy error page)
  }
  return new ApiError(response.status, {
    title: response.statusText || 'Error',
    status: response.status,
    code: response.status >= 500 ? 'internal_error' : 'request_error',
  })
}

async function getJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(url, { headers: { Accept: 'application/json, application/problem+json' }, signal })
  if (!response.ok) throw await readProblem(response)
  return (await response.json()) as T
}

export function fetchRegions(signal?: AbortSignal): Promise<SidoNode[]> {
  return getJson('/api/v1/regions', signal)
}

/** At most 20 ids per call (API limit). */
export const MAX_RESOLUTION_IDS = 20

export function fetchResolutions(ids: number[], signal?: AbortSignal): Promise<RegionResolution[]> {
  return getJson(`/api/v1/regions/resolutions?ids=${ids.join(',')}`, signal)
}

/** The one search condition parameter (`title`, `author` or `publisher`); the APIs no longer read `q` (ADR-0025). */
export function conditionParam(field: SearchField, text: string): Record<string, string> {
  return { [field]: text }
}

export function fetchLibraryPage(
  libraryId: number,
  field: SearchField,
  query: string,
  page: number,
): Promise<LibraryPage> {
  const params = new URLSearchParams({ ...conditionParam(field, query), page: String(page) })
  return getJson(`/api/v1/availability/libraries/${libraryId}/ebooks?${params}`)
}

/** What `GET /api/v1/books` searches with (design-delta API). Empty fields are left out. */
export interface BookQuery {
  title: string | null
  author: string | null
  publisher: string | null
  exact: ReadonlyArray<SearchField>
  roles: ReadonlyArray<BookRole>
  sort: BookSort
  scope: LibraryScope
  visit: VisitFilter
  residenceRegionIds: number[]
  workRegionIds: number[]
  /** Libraries added by hand (cycle 06); sent only when not empty, never put in the screen address (C17). */
  libraryIds: number[]
  page: number
}

/** The plan part of a request: scope, visit, the regions and the libraries added by hand. */
export interface PlanParams {
  scope: LibraryScope
  visit: VisitFilter
  residenceRegionIds: number[]
  workRegionIds: number[]
  libraryIds: number[]
}

/** The plan part shared by the book APIs and the holding streams. */
function planParams(search: URLSearchParams, plan: PlanParams) {
  if (plan.scope !== 'all') search.set('scope', plan.scope)
  if (plan.visit !== 'any') search.set('visit', plan.visit)
  if (plan.libraryIds.length) search.set('libraryIds', plan.libraryIds.join(','))
  if (plan.residenceRegionIds.length) search.set('residenceRegionIds', plan.residenceRegionIds.join(','))
  if (plan.workRegionIds.length) search.set('workRegionIds', plan.workRegionIds.join(','))
}

export function bookSearchParams(query: BookQuery): URLSearchParams {
  const search = new URLSearchParams()
  if (query.title) search.set('title', query.title)
  if (query.author) search.set('author', query.author)
  if (query.publisher) search.set('publisher', query.publisher)
  // exact only for filled fields, roles only with an author (the server ignores the rest)
  const exact = query.exact.filter((field) => query[field])
  if (exact.length) search.set('exact', exact.join(','))
  if (query.author && query.roles.length) search.set('roles', query.roles.join(','))
  if (query.sort !== 'default') search.set('sort', query.sort)
  planParams(search, query)
  if (query.page > 1) search.set('page', String(query.page))
  return search
}

export function fetchBooks(query: BookQuery, signal?: AbortSignal): Promise<BookPage> {
  return getJson(`/api/v1/books?${bookSearchParams(query)}`, signal)
}

export function fetchBook(bookId: string, plan: PlanParams, signal?: AbortSignal): Promise<BookDetail> {
  const search = new URLSearchParams()
  planParams(search, plan)
  const suffix = search.size ? `?${search}` : ''
  return getJson(`/api/v1/books/${encodeURIComponent(bookId)}${suffix}`, signal)
}

/**
 * `GET /api/v1/libraries` (cycle 06, ADR-0049): every enabled library in id order with the eligibility for the
 * regions sent. Public, no login. The 내 도서관 screen sends the resolved region ids.
 */
export function fetchLibraries(
  residenceRegionIds: number[],
  workRegionIds: number[],
  signal?: AbortSignal,
): Promise<LibraryEligibilityRow[]> {
  const search = new URLSearchParams()
  if (residenceRegionIds.length) search.set('residenceRegionIds', residenceRegionIds.join(','))
  if (workRegionIds.length) search.set('workRegionIds', workRegionIds.join(','))
  const suffix = search.size ? `?${search}` : ''
  return getJson<{ libraries: LibraryEligibilityRow[] }>(`/api/v1/libraries${suffix}`, signal).then(
    (body) => body.libraries,
  )
}

/**
 * `GET /api/v1/picks` (cycle 14): the National Library of Korea's librarian picks of the latest six months by field,
 * each marked whether a plan library holds it. Public, no login; sends the same plan as the book search (AC-7).
 */
export function fetchPicks(plan: PlanParams, signal?: AbortSignal): Promise<Picks> {
  const search = new URLSearchParams()
  planParams(search, plan)
  const suffix = search.size ? `?${search}` : ''
  return getJson(`/api/v1/picks${suffix}`, signal)
}

/**
 * `GET /api/v1/join-surveys/latest` (cycle 13, ADR-0093): the date the join rules were last surveyed, shown on S12.
 * Public, no login.
 */
export function fetchLatestJoinSurvey(signal?: AbortSignal): Promise<LatestJoinSurvey> {
  return getJson('/api/v1/join-surveys/latest', signal)
}
