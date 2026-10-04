import { readProblem } from './client'
import type { ConsentInput, Me, SignupInfo } from './types'

/**
 * Login API (cycle 05, handoff step 11). The token cookies are HttpOnly: this code never reads them. The only
 * cookie it reads is `XSRF-TOKEN`, sent back as the `X-XSRF-TOKEN` header on every change request (C13).
 */

const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'
const REFRESH_LOCK = 'auth-refresh'

/** Reads one cookie by name; the other cookies are not looked at. */
export function readCsrfCookie(cookies: string = document.cookie): string | null {
  for (const part of cookies.split(';')) {
    const [name, ...rest] = part.trim().split('=')
    if (name === CSRF_COOKIE) return decodeURIComponent(rest.join('='))
  }
  return null
}

/** Asks the server for a new `XSRF-TOKEN` cookie (204). */
export async function fetchCsrfToken(): Promise<void> {
  const response = await fetch('/api/v1/auth/csrf-token', { credentials: 'same-origin' })
  if (!response.ok) throw await readProblem(response)
}

function isChange(init?: RequestInit): boolean {
  const method = (init?.method ?? 'GET').toUpperCase()
  return method !== 'GET' && method !== 'HEAD'
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

/**
 * One request with the CSRF header on changes. Without the cookie a token is fetched first. A 403 `csrf_invalid`
 * gets a new token and one more try: Spring renews the token when a login succeeds, so the first change after
 * coming back from the provider can meet an old one.
 */
async function send(url: string, init: RequestInit = {}): Promise<Response> {
  if (!isChange(init)) return fetch(url, { credentials: 'same-origin', ...init })
  if (readCsrfCookie() === null) await fetchCsrfToken()
  const withToken = () => {
    const headers = new Headers(init.headers)
    headers.set(CSRF_HEADER, readCsrfCookie() ?? '')
    return fetch(url, { credentials: 'same-origin', ...init, headers })
  }
  const response = await withToken()
  if (!(await isCsrfRejection(response))) return response
  await fetchCsrfToken()
  return withToken()
}

/** Called when a refresh fails: the browser is not signed in any more. */
let signedOutListener: (() => void) | null = null

export function onSignedOut(listener: (() => void) | null): void {
  signedOutListener = listener
}

/** Runs `task` while holding the cross-tab refresh lock; a chain in this tab when Web Locks is missing. */
let localChain: Promise<unknown> = Promise.resolve()

function withRefreshLock<T>(task: () => Promise<T>): Promise<T> {
  if (typeof navigator !== 'undefined' && navigator.locks?.request) {
    return navigator.locks.request(REFRESH_LOCK, task) as Promise<T>
  }
  const run = localChain.then(task, task)
  localChain = run.catch(() => undefined)
  return run
}

/**
 * A request to the account API (`/api/v1/me...`). On 401 (access token expired), in order (design-delta flow
 * "토큰 갱신과 교체"): take the refresh lock; send the request again first (another tab may have refreshed);
 * still 401 → `POST /api/v1/auth/refresh`; 204 → send the request again; 401 → signed out, no message. Public APIs
 * never come here.
 */
export async function meRequest(url: string, init: RequestInit = {}): Promise<Response> {
  const first = await send(url, init)
  if (first.status !== 401) return first
  return withRefreshLock(async () => {
    const again = await send(url, init)
    if (again.status !== 401) return again
    if (await refresh()) return send(url, init)
    signedOutListener?.()
    return again
  })
}

/** `POST /api/v1/auth/refresh`: true when both cookies were renewed. */
export async function refresh(): Promise<boolean> {
  const response = await send('/api/v1/auth/refresh', { method: 'POST' })
  return response.status === 204
}

export type MeResult = { state: 'off' } | { state: 'signed-out' } | { state: 'signed-in'; me: Me }

/**
 * Whether login is on and who is signed in (user decision 2026-10-04): 404 = login is off, 401 after the refresh
 * steps = signed out, 200 = signed in.
 */
export async function fetchMe(): Promise<MeResult> {
  const response = await meRequest('/api/v1/me', { headers: { Accept: 'application/json, application/problem+json' } })
  if (response.status === 404) return { state: 'off' }
  if (response.status === 401) return { state: 'signed-out' }
  if (!response.ok) throw await readProblem(response)
  return { state: 'signed-in', me: (await response.json()) as Me }
}

/** The login buttons to show (user decision 2026-10-04: `GET /api/v1/auth/providers`). */
export async function fetchProviders(): Promise<Me['loginMethods']> {
  const response = await fetch('/api/v1/auth/providers', { credentials: 'same-origin' })
  if (!response.ok) throw await readProblem(response)
  return (await response.json()) as Me['loginMethods']
}

/** Idempotent: the server clears both cookies whether or not a login existed. */
export async function logout(): Promise<void> {
  const response = await send('/api/v1/auth/logout', { method: 'POST' })
  if (!response.ok) throw await readProblem(response)
}

export async function getSignup(): Promise<SignupInfo> {
  const response = await fetch('/api/v1/auth/signup', { credentials: 'same-origin' })
  if (!response.ok) throw await readProblem(response)
  return (await response.json()) as SignupInfo
}

/** 201 with the login cookies; 400 `validation_error`, 404 `signup_not_found`, 409 `document_version_mismatch`. */
export async function postSignup(consents: ConsentInput[]): Promise<void> {
  const response = await send('/api/v1/auth/signup', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ consents }),
  })
  if (!response.ok) throw await readProblem(response)
}

export async function cancelSignup(): Promise<void> {
  const response = await send('/api/v1/auth/signup', { method: 'DELETE' })
  if (!response.ok) throw await readProblem(response)
}

/** Withdrawal after re-authentication: 204; 403 `reauthentication_required`/`_expired`; 500 `internal_error`. */
export async function deleteMe(): Promise<void> {
  const response = await meRequest('/api/v1/me', { method: 'DELETE' })
  if (!response.ok) throw await readProblem(response)
}

/** Where the login and re-authentication buttons go (full page navigation to the provider). */
export function loginStartUrl(provider: Me['loginMethods'][number], purpose: 'login' | 'withdraw'): string {
  const id = provider.toLowerCase() + (purpose === 'withdraw' ? '-reauth' : '')
  return `/api/v1/auth/oauth2/authorization/${id}`
}
