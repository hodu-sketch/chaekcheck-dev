import { meRequest } from './auth'
import { ApiError, readProblem } from './client'
import type { MyLibraries, MyRegions } from './types'

/**
 * 내 도서관 API (cycle 06, design-delta API). Every call goes through `meRequest`: the 401 refresh under the Web Lock
 * and, for changes, the CSRF header with one retry on `csrf_invalid` (C20). Errors are thrown as ApiError; a 401 that
 * is still 401 after the refresh means the browser is signed out (the auth state is told by `meRequest`).
 */

const JSON_ACCEPT = { Accept: 'application/json, application/problem+json' }

async function json<T>(response: Response): Promise<T> {
  if (!response.ok) throw await readProblem(response)
  return (await response.json()) as T
}

async function noContent(response: Response): Promise<void> {
  if (!response.ok) throw await readProblem(response)
}

export async function getMyRegions(): Promise<MyRegions> {
  return json(await meRequest('/api/v1/me/regions', { headers: JSON_ACCEPT }))
}

/** Replaces both fields in the given order; 400 `validation_error`, 422 `invalid_region`. */
export async function putMyRegions(regions: MyRegions): Promise<MyRegions> {
  return json(
    await meRequest('/api/v1/me/regions', {
      method: 'PUT',
      headers: { ...JSON_ACCEPT, 'Content-Type': 'application/json' },
      body: JSON.stringify(regions),
    }),
  )
}

export async function getMyLibraries(): Promise<MyLibraries> {
  return json(await meRequest('/api/v1/me/libraries', { headers: JSON_ACCEPT }))
}

/** 204 also when it is already there; 404 `library_not_found`, 409 `library_limit_reached`. */
export async function addMyLibrary(libraryId: number): Promise<void> {
  return noContent(await meRequest(`/api/v1/me/libraries/${libraryId}`, { method: 'PUT', headers: JSON_ACCEPT }))
}

/** 204 also when it was not there. */
export async function removeMyLibrary(libraryId: number): Promise<void> {
  return noContent(await meRequest(`/api/v1/me/libraries/${libraryId}`, { method: 'DELETE', headers: JSON_ACCEPT }))
}

export { ApiError }
