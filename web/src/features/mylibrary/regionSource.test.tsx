import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider, useAuth } from '../auth/AuthContext'
import { NoticeBar, NoticeProvider } from '../auth/NoticeContext'
import { RegionProvider, useRegions } from '../region/RegionContext'
import { REGION_STORAGE_KEY } from '../region/regionStorage'
import { MyLibraryProvider } from './MyLibraryContext'
import { RegionImportDialog } from './RegionImportDialog'
import { REGION_IMPORT_KEY } from './regionImport'

/**
 * Cycle 06 step 9 against a fake server: where the regions come from before and after login (C18), what is sent
 * (AC-2), S11 (AC-3) and abolished saved regions (AC-9).
 */

const TREE = [
  {
    id: 11,
    name: '서울특별시',
    shortName: '서울',
    children: [
      { id: 1102, name: '중구' },
      { id: 1114, name: '마포구' },
    ],
  },
]

const RESOLUTIONS: Record<number, unknown> = {
  1102: { id: 1102, status: 'ACTIVE', label: '서울 중구', replacements: [] },
  1114: { id: 1114, status: 'ACTIVE', label: '서울 마포구', replacements: [] },
  9001: { id: 9001, status: 'REPLACED', label: '시험 폐지구', replacements: [{ id: 1102, label: '서울 중구' }] },
  9002: { id: 9002, status: 'SPLIT', label: '시험 분할구', replacements: [] },
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

interface Fake {
  signedIn: boolean
  serverRegions: { residenceRegionIds: number[]; workRegionIds: number[] }
  putStatus: number
  calls: Array<{ method: string; url: string; body?: unknown }>
}

function fakeServer(fake: Fake) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    fake.calls.push({ method, url, body: init?.body ? JSON.parse(init.body as string) : undefined })
    if (url === '/api/v1/me') return fake.signedIn ? json(200, { id: 7, loginMethods: ['KAKAO'], consents: [] }) : json(401, {})
    if (url === '/api/v1/auth/refresh') return json(401, { code: 'unauthorized' })
    if (url === '/api/v1/auth/logout') return new Response(null, { status: 204 })
    if (url === '/api/v1/regions') return json(200, TREE)
    if (url.startsWith('/api/v1/regions/resolutions?ids=')) {
      const ids = url.split('=')[1].split(',').map(Number)
      return json(200, ids.map((id) => RESOLUTIONS[id] ?? { id, status: 'UNKNOWN', label: null, replacements: [] }))
    }
    if (url === '/api/v1/me/regions' && method === 'GET') return json(200, fake.serverRegions)
    if (url === '/api/v1/me/regions' && method === 'PUT') {
      if (fake.putStatus !== 200) return json(fake.putStatus, { code: 'internal_error' })
      fake.serverRegions = JSON.parse(init!.body as string)
      return json(200, fake.serverRegions)
    }
    if (url === '/api/v1/me/libraries') return json(200, { libraryIds: [], max: 20 })
    throw new Error(`unexpected ${method} ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
}

function Probe() {
  const { regions, source, ready, notices, setRegions } = useRegions()
  const { signOut } = useAuth()
  return (
    <div>
      <p data-testid="source">{source}</p>
      <p data-testid="ready">{String(ready)}</p>
      <p data-testid="regions">{JSON.stringify(regions)}</p>
      <ul>
        {notices.map((notice) => (
          <li key={notice.message}>{notice.message}</li>
        ))}
      </ul>
      <button type="button" onClick={() => void setRegions({ residence: [1102], work: [] }).catch(() => {})}>
        save
      </button>
      <button type="button" onClick={() => void signOut()}>
        logout
      </button>
    </div>
  )
}

function renderApp() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <NoticeProvider>
          <MyLibraryProvider>
            <RegionProvider>
              {/* the notice bar follows the page, as in AppLayout */}
              <MemoryRouter>
                <NoticeBar />
                <Probe />
                <RegionImportDialog />
              </MemoryRouter>
            </RegionProvider>
          </MyLibraryProvider>
        </NoticeProvider>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

const text = (id: string) => screen.getByTestId(id).textContent
let fake: Fake

beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=csrf; path=/'
  fake = { signedIn: false, serverRegions: { residenceRegionIds: [], workRegionIds: [] }, putStatus: 200, calls: [] }
  fakeServer(fake)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  localStorage.clear()
})

const browserValue = (residence: number[], work: number[]) =>
  localStorage.setItem(REGION_STORAGE_KEY, JSON.stringify({ residence, work }))

describe('before login (cycle 05 behaviour, AC-2, AC-14)', () => {
  it('uses this browser and never sends regions to be saved', async () => {
    browserValue([1114], [])
    renderApp()
    await waitFor(() => expect(text('ready')).toBe('true'))
    expect(text('source')).toBe('browser')
    expect(text('regions')).toBe('{"residence":[1114],"work":[]}')

    await userEvent.setup().click(screen.getByRole('button', { name: 'save' }))

    expect(JSON.parse(localStorage.getItem(REGION_STORAGE_KEY)!)).toEqual({ residence: [1102], work: [] })
    expect(fake.calls.filter((call) => call.url.startsWith('/api/v1/me/'))).toEqual([])
  })
})

describe('signed in (C18)', () => {
  it('uses only the saved values: this browser is neither read nor written', async () => {
    fake.signedIn = true
    fake.serverRegions = { residenceRegionIds: [1102], workRegionIds: [] }
    browserValue([1114], [])
    localStorage.setItem(REGION_IMPORT_KEY, '[7]')
    const getItem = vi.spyOn(Storage.prototype, 'getItem')
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    renderApp()

    await waitFor(() => expect(text('regions')).toBe('{"residence":[1102],"work":[]}'))
    expect(text('source')).toBe('account')
    await userEvent.setup().click(screen.getByRole('button', { name: 'save' }))
    await waitFor(() => expect(fake.calls.some((call) => call.method === 'PUT')).toBe(true))

    expect(fake.calls.find((call) => call.method === 'PUT')?.body).toEqual({ residenceRegionIds: [1102], workRegionIds: [] })
    // S11's one read of the answered accounts is the only storage access besides it
    expect(getItem.mock.calls.filter(([key]) => key === REGION_STORAGE_KEY).length).toBeLessThanOrEqual(1)
    expect(setItem.mock.calls.filter(([key]) => key === REGION_STORAGE_KEY)).toEqual([])
  })

  it('searches without regions while the saved values load, then uses them', async () => {
    fake.signedIn = true
    fake.serverRegions = { residenceRegionIds: [1114], workRegionIds: [] }
    renderApp()

    await waitFor(() => expect(text('source')).toBe('account'))
    await waitFor(() => expect(text('regions')).toBe('{"residence":[1114],"work":[]}'))
    expect(text('ready')).toBe('true')
  })

  it('goes back to this browser after logout', async () => {
    fake.signedIn = true
    fake.serverRegions = { residenceRegionIds: [1102], workRegionIds: [] }
    browserValue([1114], [])
    localStorage.setItem(REGION_IMPORT_KEY, '[7]')
    renderApp()
    await waitFor(() => expect(text('regions')).toBe('{"residence":[1102],"work":[]}'))

    await userEvent.setup().click(screen.getByRole('button', { name: 'logout' }))

    await waitFor(() => expect(text('regions')).toBe('{"residence":[1114],"work":[]}'))
    expect(text('source')).toBe('browser')
  })

  it('abolished saved regions are resolved for the screen with a notice, not saved back (AC-9, C19)', async () => {
    fake.signedIn = true
    fake.serverRegions = { residenceRegionIds: [9001], workRegionIds: [9002] }
    renderApp()

    await waitFor(() => expect(text('regions')).toBe('{"residence":[1102],"work":[]}'))
    expect(screen.getByText('행정구역 변경으로 시험 폐지구가 서울 중구로 바뀌었습니다')).toBeTruthy()
    expect(screen.getByText('시험 분할구가 여러 지역으로 나뉘었습니다. 내 지역을 다시 골라 주세요')).toBeTruthy()
    expect(fake.calls.filter((call) => call.method === 'PUT')).toEqual([])
  })
})

describe('S11 (AC-3)', () => {
  async function openS11(browser: [number[], number[]]) {
    fake.signedIn = true
    browserValue(...browser)
    renderApp()
    return screen.findByRole('dialog', { name: '이 브라우저에 고른 지역이 있어요. 계정에 저장할까요?' })
  }

  it('계정에 저장 sends the resolved regions and remembers the answer', async () => {
    const dialog = await openS11([[1114, 9001], [9002]])
    expect(await screen.findByText('행정구역이 바뀌어 서울 중구로 저장돼요')).toBeTruthy()
    expect(dialog.textContent).toContain('서울 마포구, 서울 중구')

    await userEvent.setup().click(screen.getByRole('button', { name: '계정에 저장' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(fake.calls.find((call) => call.method === 'PUT')?.body).toEqual({
      residenceRegionIds: [1114, 1102],
      workRegionIds: [],
    })
    expect(JSON.parse(localStorage.getItem(REGION_IMPORT_KEY)!)).toEqual([7])
    // the split region asks to choose again, and the screen now uses the saved values
    expect(screen.getByRole('status').textContent).toContain('지역을 다시 골라 주세요')
    await waitFor(() => expect(text('regions')).toBe('{"residence":[1114,1102],"work":[]}'))
    // this browser's own value is left as it was
    expect(JSON.parse(localStorage.getItem(REGION_STORAGE_KEY)!)).toEqual({ residence: [1114, 9001], work: [9002] })
  })

  it('저장하지 않기 sends nothing and remembers the answer', async () => {
    await openS11([[1114], []])

    await userEvent.setup().click(screen.getByRole('button', { name: '저장하지 않기' }))

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(fake.calls.filter((call) => call.method === 'PUT')).toEqual([])
    expect(JSON.parse(localStorage.getItem(REGION_IMPORT_KEY)!)).toEqual([7])
  })

  it('closing without an answer remembers nothing', async () => {
    await openS11([[1114], []])

    await userEvent.setup().keyboard('{Escape}')

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(localStorage.getItem(REGION_IMPORT_KEY)).toBeNull()
  })

  it('a failed save keeps the dialog open with the message and can be tried again', async () => {
    fake.putStatus = 500
    await openS11([[1114], []])
    const user = userEvent.setup()
    await waitFor(() => expect((screen.getByRole('button', { name: '계정에 저장' }) as HTMLButtonElement).disabled).toBe(false))

    await user.click(screen.getByRole('button', { name: '계정에 저장' }))

    expect(await screen.findByText('저장하지 못했어요. 잠시 뒤 다시 해 주세요')).toBeTruthy()
    expect(localStorage.getItem(REGION_IMPORT_KEY)).toBeNull()
    fake.putStatus = 200
    await user.click(screen.getByRole('button', { name: '계정에 저장' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('is not offered when the account already has regions or this browser has none', async () => {
    fake.signedIn = true
    fake.serverRegions = { residenceRegionIds: [1102], workRegionIds: [] }
    browserValue([1114], [])
    renderApp()
    await waitFor(() => expect(text('regions')).toBe('{"residence":[1102],"work":[]}'))
    await act(async () => {})
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
