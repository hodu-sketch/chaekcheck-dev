import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { sendUsageEvent, setUsageLoginFeature } from './usageEvents'

/** Cycle 15 step 8: the one place that sends usage reports (AC-9 web part). */

const EVENT = { type: 'picks_list_view', list: 'librarian', category: '11' } as const

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function clearCookies() {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
}

/** The posts to the usage address, with their CSRF header. */
function posts(fetchMock: ReturnType<typeof vi.fn>) {
  return fetchMock.mock.calls
    .filter(([url]) => url === '/api/v1/usage-events')
    .map(([, init]) => init as RequestInit & { headers: Record<string, string> })
}

async function settle() {
  for (let i = 0; i < 10; i++) await Promise.resolve()
  await new Promise((resolve) => setTimeout(resolve, 0))
}

beforeEach(() => clearCookies())

afterEach(() => {
  vi.unstubAllGlobals()
  setUsageLoginFeature('unknown')
  clearCookies()
})

describe('sendUsageEvent', () => {
  it('login off: no CSRF header, keepalive, the JSON body, no login state', async () => {
    setUsageLoginFeature('off')
    document.cookie = 'XSRF-TOKEN=csrf-1; path=/'
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    sendUsageEvent(EVENT)
    await settle()

    const [post] = posts(fetchMock)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(post.method).toBe('POST')
    expect(post.keepalive).toBe(true)
    expect(post.credentials).toBe('same-origin')
    expect(post.headers['X-XSRF-TOKEN']).toBeUndefined()
    expect(JSON.parse(post.body as string)).toEqual({ type: 'picks_list_view', list: 'librarian', category: '11' })
  })

  it('login on: the token from the cookie goes in the header', async () => {
    setUsageLoginFeature('on')
    document.cookie = 'XSRF-TOKEN=csrf-1; path=/'
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    sendUsageEvent(EVENT)
    await settle()

    expect(posts(fetchMock)).toHaveLength(1)
    expect(posts(fetchMock)[0].headers['X-XSRF-TOKEN']).toBe('csrf-1')
  })

  it('login on without the cookie: gets a token first', async () => {
    setUsageLoginFeature('on')
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/api/v1/auth/csrf-token') {
        document.cookie = 'XSRF-TOKEN=csrf-2; path=/'
        return new Response(null, { status: 204 })
      }
      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)

    sendUsageEvent(EVENT)
    await settle()

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['/api/v1/auth/csrf-token', '/api/v1/usage-events'])
    expect(posts(fetchMock)[0].headers['X-XSRF-TOKEN']).toBe('csrf-2')
  })

  it('a 403 csrf_invalid gets a new token and one more try, never a third', async () => {
    setUsageLoginFeature('on')
    document.cookie = 'XSRF-TOKEN=old; path=/'
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/api/v1/auth/csrf-token') {
        document.cookie = 'XSRF-TOKEN=new; path=/'
        return new Response(null, { status: 204 })
      }
      return json(403, { code: 'csrf_invalid' })
    })
    vi.stubGlobal('fetch', fetchMock)

    sendUsageEvent(EVENT)
    await settle()

    expect(posts(fetchMock).map((post) => post.headers['X-XSRF-TOKEN'])).toEqual(['old', 'new'])
  })

  it('login state not known yet: no token without the cookie, and a 403 is still answered once', async () => {
    const fetchMock = vi.fn(async (url: string): Promise<Response> => {
      if (url === '/api/v1/auth/csrf-token') {
        document.cookie = 'XSRF-TOKEN=csrf-3; path=/'
        return new Response(null, { status: 204 })
      }
      return posts(fetchMock).length === 1 ? json(403, { code: 'csrf_invalid' }) : new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)

    sendUsageEvent(EVENT)
    await settle()

    expect(posts(fetchMock).map((post) => post.headers['X-XSRF-TOKEN'])).toEqual([undefined, 'csrf-3'])
  })

  it('a failure shows nothing, throws nothing and is not sent again', async () => {
    setUsageLoginFeature('off')
    const fetchMock = vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    })
    vi.stubGlobal('fetch', fetchMock)

    expect(() => sendUsageEvent(EVENT)).not.toThrow()
    await settle()

    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
