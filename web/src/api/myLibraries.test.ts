import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { onSignedOut } from './auth'
import { ApiError } from './client'
import { addMyLibrary, getMyLibraries, getMyRegions, putMyRegions, removeMyLibrary } from './myLibraries'

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function clearCsrf() {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
}

beforeEach(() => {
  clearCsrf()
  document.cookie = 'XSRF-TOKEN=csrf-1; path=/'
})

afterEach(() => {
  vi.unstubAllGlobals()
  onSignedOut(null)
  clearCsrf()
})

function headerOf(init: RequestInit | undefined, name: string): string | null {
  return new Headers(init?.headers).get(name)
}

describe('내 도서관 API (C20)', () => {
  it('reads go to /api/v1/me without a CSRF header', async () => {
    const fetchMock = vi.fn(async (url: string) =>
      url === '/api/v1/me/regions'
        ? json(200, { residenceRegionIds: [1114], workRegionIds: [] })
        : json(200, { libraryIds: [12, 207], max: 20 }),
    )
    vi.stubGlobal('fetch', fetchMock)

    expect(await getMyRegions()).toEqual({ residenceRegionIds: [1114], workRegionIds: [] })
    expect(await getMyLibraries()).toEqual({ libraryIds: [12, 207], max: 20 })
    for (const [, init] of fetchMock.mock.calls as unknown as Array<[string, RequestInit]>) {
      expect(headerOf(init, 'X-XSRF-TOKEN')).toBeNull()
    }
  })

  it('changes carry the CSRF header and the body', async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/v1/me/regions') return json(200, JSON.parse(init!.body as string))
      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await putMyRegions({ residenceRegionIds: [1114, 1102], workRegionIds: [] })
    await addMyLibrary(12)
    await removeMyLibrary(12)

    const calls = fetchMock.mock.calls as unknown as Array<[string, RequestInit]>
    expect(calls.map(([url, init]) => `${init.method} ${url}`)).toEqual([
      'PUT /api/v1/me/regions',
      'PUT /api/v1/me/libraries/12',
      'DELETE /api/v1/me/libraries/12',
    ])
    for (const [, init] of calls) expect(headerOf(init, 'X-XSRF-TOKEN')).toBe('csrf-1')
    expect(JSON.parse(calls[0][1].body as string)).toEqual({ residenceRegionIds: [1114, 1102], workRegionIds: [] })
  })

  it('401 refreshes once and sends the change again', async () => {
    let refreshed = false
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/api/v1/auth/refresh') {
        refreshed = true
        return new Response(null, { status: 204 })
      }
      return refreshed ? new Response(null, { status: 204 }) : json(401, { code: 'unauthorized' })
    })
    vi.stubGlobal('fetch', fetchMock)

    await addMyLibrary(12)

    const urls = (fetchMock.mock.calls as unknown as Array<[string]>).map(([url]) => url)
    expect(urls.filter((url) => url === '/api/v1/auth/refresh')).toHaveLength(1)
    expect(urls.at(-1)).toBe('/api/v1/me/libraries/12')
  })

  it('a failed refresh signs out and the call throws 401', async () => {
    const signedOut = vi.fn()
    onSignedOut(signedOut)
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => json(401, { code: 'unauthorized' })),
    )

    await expect(getMyRegions()).rejects.toMatchObject({ status: 401 })
    expect(signedOut).toHaveBeenCalled()
  })

  it('409 comes back as ApiError with its code', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => json(409, { title: 'Conflict', status: 409, code: 'library_limit_reached' })),
    )

    const error = await addMyLibrary(30).catch((failure: unknown) => failure)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).problem.code).toBe('library_limit_reached')
  })
})
