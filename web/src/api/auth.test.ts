import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMe, logout, meRequest, onSignedOut, readCsrfCookie } from './auth'

/** A cross-tab lock like `navigator.locks` (jsdom has none): callers wait their turn. */
function installLocks() {
  let chain: Promise<unknown> = Promise.resolve()
  const request = vi.fn((_name: string, task: () => Promise<unknown>) => {
    const run = chain.then(task)
    chain = run.catch(() => undefined)
    return run
  })
  Object.defineProperty(navigator, 'locks', { value: { request }, configurable: true })
  return request
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function clearCookies() {
  for (const name of ['XSRF-TOKEN', 'other']) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`
}

const ME = { id: 7, loginMethods: ['KAKAO'], consents: [] }

beforeEach(() => {
  clearCookies()
  document.cookie = 'XSRF-TOKEN=csrf-1; path=/'
})

afterEach(() => {
  vi.unstubAllGlobals()
  onSignedOut(null)
  Reflect.deleteProperty(navigator, 'locks')
  clearCookies()
})

describe('refresh with Web Locks', () => {
  it('two tabs whose access token expired refresh only once', async () => {
    installLocks()
    let refreshed = false
    let refreshCalls = 0
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/api/v1/me') return refreshed ? json(200, ME) : json(401, { code: 'unauthorized' })
      if (url === '/api/v1/auth/refresh') {
        refreshCalls++
        refreshed = true
        return new Response(null, { status: 204 })
      }
      throw new Error(`unexpected ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const [a, b] = await Promise.all([meRequest('/api/v1/me'), meRequest('/api/v1/me')])

    expect(a.status).toBe(200)
    expect(b.status).toBe(200)
    expect(refreshCalls).toBe(1)
  })

  it('the tab holding the lock sends the original request again before refreshing', async () => {
    installLocks()
    const answers = [json(401, { code: 'unauthorized' }), json(200, ME)]
    const fetchMock = vi.fn(async (_url: string) => answers.shift()!)
    vi.stubGlobal('fetch', fetchMock)

    const response = await meRequest('/api/v1/me')

    expect(response.status).toBe(200)
    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual(['/api/v1/me', '/api/v1/me'])
  })

  it('a failed refresh means signed out, without a message', async () => {
    installLocks()
    const signedOut = vi.fn()
    onSignedOut(signedOut)
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) =>
        url === '/api/v1/auth/refresh' ? json(401, { code: 'unauthorized' }) : json(401, { code: 'unauthorized' }),
      ),
    )

    expect(await fetchMe()).toEqual({ state: 'signed-out' })
    expect(signedOut).toHaveBeenCalledTimes(1)
  })

  it('works without Web Locks too (one tab)', async () => {
    let refreshed = false
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        if (url === '/api/v1/auth/refresh') {
          refreshed = true
          return new Response(null, { status: 204 })
        }
        return refreshed ? json(200, ME) : json(401, { code: 'unauthorized' })
      }),
    )

    expect(await fetchMe()).toEqual({ state: 'signed-in', me: ME })
  })
})

describe('login on or off', () => {
  it('404 means login is switched off on the server', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(404, { code: 'not_found' })))

    expect(await fetchMe()).toEqual({ state: 'off' })
  })
})

describe('CSRF', () => {
  it('a change carries the XSRF-TOKEN cookie value as X-XSRF-TOKEN', async () => {
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await logout()

    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(new Headers(init.headers).get('X-XSRF-TOKEN')).toBe('csrf-1')
  })

  it('without the cookie a token is fetched first', async () => {
    clearCookies()
    const fetchMock = vi.fn(async (url: string, _init?: RequestInit) => {
      if (url === '/api/v1/auth/csrf-token') {
        document.cookie = 'XSRF-TOKEN=csrf-new; path=/'
        return new Response(null, { status: 204 })
      }
      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await logout()

    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual(['/api/v1/auth/csrf-token', '/api/v1/auth/logout'])
    expect(new Headers((fetchMock.mock.calls[1][1] as RequestInit).headers).get('X-XSRF-TOKEN')).toBe('csrf-new')
  })

  it('a csrf_invalid 403 gets a new token and exactly one more try', async () => {
    const fetchMock = vi.fn(async (url: string, _init?: RequestInit) => {
      if (url === '/api/v1/auth/csrf-token') {
        document.cookie = 'XSRF-TOKEN=csrf-2; path=/'
        return new Response(null, { status: 204 })
      }
      return json(403, { title: 'Forbidden', status: 403, code: 'csrf_invalid' })
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(logout()).rejects.toMatchObject({ status: 403 })

    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      '/api/v1/auth/logout',
      '/api/v1/auth/csrf-token',
      '/api/v1/auth/logout',
    ])
    expect(new Headers((fetchMock.mock.calls[2][1] as RequestInit).headers).get('X-XSRF-TOKEN')).toBe('csrf-2')
  })

  it('only the XSRF-TOKEN cookie is read', () => {
    expect(readCsrfCookie('access_token=secret; XSRF-TOKEN=tok%3D1; refresh_token=secret2')).toBe('tok=1')
    expect(readCsrfCookie('access_token=secret; refresh_token=secret2')).toBeNull()
  })
})
