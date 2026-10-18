import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { sendUsageEvent } from '../../api/usageEvents'
import { AuthProvider } from '../auth/AuthContext'
import { MyLibraryProvider } from '../mylibrary/MyLibraryContext'
import { RegionProvider, useRegions } from './RegionContext'

/**
 * Cycle 22 D6: the save order of `setRegions` after login. One `PUT /api/v1/me/regions` at a time, only the latest
 * value after it, back to the last saved value on failure, the resolution notice on 422.
 */

vi.mock('../../api/usageEvents', () => ({ sendUsageEvent: vi.fn(), setUsageLoginFeature: vi.fn() }))

type Regions = { residenceRegionIds: number[]; workRegionIds: number[] }

interface Put {
  body: Regions
  answer: (status?: number, code?: string) => void
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

let puts: Put[]
let inFlight: number
let mostInFlight: number
let searches: number

beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=csrf; path=/'
  puts = []
  inFlight = 0
  mostInFlight = 0
  searches = 0
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = (init?.method ?? 'GET').toUpperCase()
      if (url === '/api/v1/me') return json(200, { id: 7, loginMethods: ['KAKAO'], consents: [] })
      if (url === '/api/v1/me/regions' && method === 'GET') return json(200, { residenceRegionIds: [1102], workRegionIds: [] })
      if (url === '/api/v1/me/libraries') return json(200, { libraryIds: [], max: 20 })
      if (url.startsWith('/api/v1/regions/resolutions?ids=')) {
        const ids = url.split('=')[1].split(',').map(Number)
        return json(
          200,
          ids.map((id) =>
            id === 9001
              ? { id, status: 'REPLACED', label: '시험 폐지구', replacements: [{ id: 1114, label: '서울 마포구' }] }
              : { id, status: 'ACTIVE', label: null, replacements: [] },
          ),
        )
      }
      if (url === '/api/v1/me/regions' && method === 'PUT') {
        const body = JSON.parse(init!.body as string) as Regions
        inFlight += 1
        mostInFlight = Math.max(mostInFlight, inFlight)
        return new Promise<Response>((resolve) =>
          puts.push({
            body,
            answer: (status = 200, code = 'internal_error') => {
              inFlight -= 1
              resolve(status === 200 ? json(200, body) : json(status, { title: 'x', status, code }))
            },
          }),
        )
      }
      if (url.includes('search') || url.startsWith('/api/v1/books')) searches += 1
      throw new Error(`unexpected ${method} ${url}`)
    }),
  )
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.mocked(sendUsageEvent).mockClear()
  localStorage.clear()
})

function Probe() {
  const { regions, ready, saveFailed, notices, setRegions } = useRegions()
  const save = (residence: number[]) => () => void setRegions({ residence, work: [] }).catch(() => undefined)
  return (
    <div>
      <p data-testid="ready">{String(ready)}</p>
      <p data-testid="regions">{regions.residence.join(',')}</p>
      <p data-testid="failed">{String(saveFailed)}</p>
      <ul>
        {notices.map((notice) => (
          <li key={notice.message}>{notice.message}</li>
        ))}
      </ul>
      <button type="button" onClick={save([1102, 1114])}>
        one
      </button>
      <button type="button" onClick={save([1114])}>
        two
      </button>
      <button type="button" onClick={save([])}>
        three
      </button>
      <button type="button" onClick={save([9001])}>
        abolished
      </button>
    </div>
  )
}

async function renderSignedIn() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <MyLibraryProvider>
          <RegionProvider>
            <Probe />
          </RegionProvider>
        </MyLibraryProvider>
      </AuthProvider>
    </QueryClientProvider>,
  )
  await waitFor(() => expect(text('regions')).toBe('1102'))
  await waitFor(() => expect(text('ready')).toBe('true'))
  return userEvent.setup()
}

const text = (id: string) => screen.getByTestId(id).textContent
const reports = () => vi.mocked(sendUsageEvent).mock.calls.map(([event]) => event)

describe('setRegions after login (cycle 22 D6)', () => {
  it('three quick checks: the PUTs never overlap and end on the last value', async () => {
    const user = await renderSignedIn()

    await user.click(screen.getByRole('button', { name: 'one' }))
    await user.click(screen.getByRole('button', { name: 'two' }))
    await user.click(screen.getByRole('button', { name: 'three' }))
    // shown at once, while only the first is on the way
    expect(text('regions')).toBe('')
    expect(puts).toHaveLength(1)
    expect(puts[0].body.residenceRegionIds).toEqual([1102, 1114])

    puts[0].answer()
    // the second check was replaced by the third: only the latest is sent
    await waitFor(() => expect(puts).toHaveLength(2))
    expect(puts[1].body.residenceRegionIds).toEqual([])
    expect(text('regions')).toBe('')
    puts[1].answer()

    await waitFor(() => expect(reports()).toHaveLength(2))
    expect(mostInFlight).toBe(1)
    expect(puts).toHaveLength(2)
    expect(text('regions')).toBe('')
    expect(text('failed')).toBe('false')
    expect(reports()).toEqual([
      { type: 'region_change', residenceRegionIds: [1102, 1114], workRegionIds: [] },
      { type: 'region_change', residenceRegionIds: [], workRegionIds: [] },
    ])
    // saving never searches (AC-20)
    expect(searches).toBe(0)
  })

  it('a failure goes back to the last saved value and says so; the next check clears it', async () => {
    const user = await renderSignedIn()

    await user.click(screen.getByRole('button', { name: 'one' }))
    puts[0].answer()
    await waitFor(() => expect(reports()).toHaveLength(1))

    await user.click(screen.getByRole('button', { name: 'two' }))
    expect(text('regions')).toBe('1114')
    puts[1].answer(500)

    await waitFor(() => expect(text('failed')).toBe('true'))
    expect(text('regions')).toBe('1102,1114')
    expect(reports()).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: 'three' }))
    expect(text('failed')).toBe('false')
  })

  it('a failure while another value waits drops it too and goes back to the loaded value', async () => {
    const user = await renderSignedIn()

    await user.click(screen.getByRole('button', { name: 'one' }))
    await user.click(screen.getByRole('button', { name: 'two' }))
    puts[0].answer(500)

    await waitFor(() => expect(text('failed')).toBe('true'))
    expect(text('regions')).toBe('1102')
    expect(puts).toHaveLength(1)
  })

  it('422 invalid_region shows the resolution notice and saves the resolved regions', async () => {
    const user = await renderSignedIn()

    await user.click(screen.getByRole('button', { name: 'abolished' }))
    puts[0].answer(422, 'invalid_region')

    await waitFor(() => expect(puts).toHaveLength(2))
    expect(puts[1].body.residenceRegionIds).toEqual([1114])
    expect(screen.getByText('행정구역 변경으로 시험 폐지구가 서울 마포구로 바뀌었습니다')).toBeTruthy()
    puts[1].answer()

    await waitFor(() => expect(reports()).toHaveLength(1))
    expect(text('regions')).toBe('1114')
    expect(text('failed')).toBe('false')
  })
})
