import { fetchCsrfToken, readCsrfCookie } from './auth'
import type { PicksListKey } from './types'

/**
 * Usage reports (cycle 15, ADR-0097): what the server does not see, sent to `POST /api/v1/usage-events` with
 * `fetch` keepalive so a report survives the page going away. The login state is never sent: the server reads it.
 * A report never shows anything, never throws and is never sent again after a failure (spec R2-7).
 */

export type ClickPlace = 'result_card' | 'join_link' | 'join_guide' | 'home_page' | 'logo' | 'summary_bar'

export type ClickScreen = 'direct_results' | 'catalog_results' | 'book_page' | 'picks' | 'my_libraries'


export type UsageEvent =
  | { type: 'library_click'; libraryId: number; place: ClickPlace; screen: ClickScreen; bookId?: number }
  | { type: 'picks_list_view'; list: PicksListKey; category: string }
  | { type: 'picks_book_view'; list: PicksListKey; category: string; bookId: number }
  | { type: 'region_change'; residenceRegionIds: number[]; workRegionIds: number[] }
  | { type: 'my_library_add' | 'my_library_remove'; libraryId: number }

const URL = '/api/v1/usage-events'
const CSRF_HEADER = 'X-XSRF-TOKEN'

/**
 * Whether the login feature is on, set by the auth context once `/api/v1/me` answered. Unknown until then: the
 * token is sent when the cookie is there, and a 403 `csrf_invalid` gets a token and one more try.
 */
type LoginFeature = 'unknown' | 'on' | 'off'

let loginFeature: LoginFeature = 'unknown'

export function setUsageLoginFeature(state: LoginFeature): void {
  loginFeature = state
}

/** Sends one report and forgets it. */
export function sendUsageEvent(event: UsageEvent): void {
  void post(event).catch(() => undefined)
}

async function post(event: UsageEvent): Promise<void> {
  const body = JSON.stringify(event)
  if (loginFeature === 'off') {
    await send(body, null)
    return
  }
  if (loginFeature === 'on' && readCsrfCookie() === null) await fetchCsrfToken()
  const response = await send(body, readCsrfCookie())
  if (!(await isCsrfRejection(response))) return
  await fetchCsrfToken()
  await send(body, readCsrfCookie())
}

function send(body: string, token: string | null): Promise<Response> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token !== null) headers[CSRF_HEADER] = token
  return fetch(URL, { method: 'POST', keepalive: true, credentials: 'same-origin', headers, body })
}

async function isCsrfRejection(response: Response): Promise<boolean> {
  if (response.status !== 403) return false
  try {
    const problem = (await response.clone().json()) as { code?: string }
    return problem.code === 'csrf_invalid'
  } catch {
    return false
  }
}
