import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthContext'
import { NoticeProvider } from '../auth/NoticeContext'
import { MyLibraryProvider } from '../mylibrary/MyLibraryContext'
import { REGION_IMPORT_KEY } from '../mylibrary/regionImport'
import { RegionProvider } from '../region/RegionContext'
import { SearchPage } from './SearchPage'
import { SETTINGS_STORAGE_KEY } from './searchSettings'

/**
 * Cycle 06 step 10 on the search screen with a fake server: the "내 도서관" line, the switch and what the book search
 * sends before and after login (AC-6, AC-7, AC-14, AC-16). 책 먼저 고르기 (`view=books`) keeps it to `/api/v1/books`.
 */

const TREE = [
  { id: 11, name: '서울특별시', shortName: '서울', children: [{ id: 1102, name: '중구' }, { id: 1114, name: '마포구' }] },
  { id: 41, name: '경기도', shortName: '경기', children: [{ id: 4110, name: '고양시' }] },
]

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

interface Fake {
  signedIn: boolean
  regions: { residenceRegionIds: number[]; workRegionIds: number[] }
  libraryIds: number[]
  librariesStatus: number
  /** Holds `GET /api/v1/me/libraries` until called. */
  holdLibraries: (() => void) | null
  books: URL[]
}

let fake: Fake

function fakeServer() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url === '/api/v1/me') return fake.signedIn ? json(200, { id: 7, loginMethods: ['KAKAO'], consents: [] }) : json(401, {})
      if (url === '/api/v1/auth/refresh') return json(401, {})
      if (url === '/api/v1/regions') return json(200, TREE)
      if (url.startsWith('/api/v1/regions/resolutions?ids=')) {
        const ids = url.split('=')[1].split(',').map(Number)
        return json(200, ids.map((id) => ({ id, status: 'ACTIVE', label: null, replacements: [] })))
      }
      if (url === '/api/v1/me/regions') return json(200, fake.regions)
      if (url === '/api/v1/me/libraries') {
        if (fake.holdLibraries === null) await Promise.resolve()
        else await new Promise<void>((resolve) => (fake.holdLibraries = resolve))
        return fake.librariesStatus === 200
          ? json(200, { libraryIds: fake.libraryIds, max: 20 })
          : json(fake.librariesStatus, { code: 'internal_error' })
      }
      if (url.startsWith('/api/v1/books?')) {
        fake.books.push(new URL(url, 'http://localhost'))
        return json(200, { items: [], page: 1, pageSize: 20, totalCount: 0, hasMore: false, collectedFrom: null, collectedTo: null })
      }
      throw new Error(`unexpected ${url}`)
    }),
  )
}

function renderSearch(address = '/search?title=사랑&view=books') {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <NoticeProvider>
          <MyLibraryProvider>
            <RegionProvider>
              <MemoryRouter initialEntries={[address]}>
                <Routes>
                  <Route path="/search" element={<SearchPage />} />
                </Routes>
              </MemoryRouter>
            </RegionProvider>
          </MyLibraryProvider>
        </NoticeProvider>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

const lastBooks = () => fake.books.at(-1)!
const switchItem = (name: string) => screen.getByRole('radio', { name })

beforeAll(() => {
  // Radix positions popovers with ResizeObserver, which jsdom does not have
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

beforeEach(() => {
  fake = {
    signedIn: true,
    regions: { residenceRegionIds: [1114], workRegionIds: [1102] },
    libraryIds: [12, 207, 3],
    librariesStatus: 200,
    holdLibraries: null,
    books: [],
  }
  // S11 is answered for this account, so it does not show up here
  localStorage.setItem(REGION_IMPORT_KEY, '[7]')
  fakeServer()
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  localStorage.clear()
})

/** Opens the "내 도서관" panel behind its button on the switch line (user request 2026-10-05). */
async function openMyLibrary() {
  await userEvent.setup().click(await screen.findByRole('button', { name: '내 도서관 관리' }))
}

describe('S1 "내 도서관" (behind "내 도서관 관리")', () => {
  it('names the regions and the added libraries, with 관리 to S10', async () => {
    renderSearch()
    await openMyLibrary()

    const panel = await screen.findByRole('dialog')
    await waitFor(() => expect(panel.textContent).toContain('서울 마포구'))
    expect(panel.textContent).toContain('직접 추가3곳')
    expect(panel.textContent).toMatch(/^내 도서관/)
    expect(panel.textContent).toContain('서울 중구')
    expect(screen.getByRole('link', { name: '관리' }).getAttribute('href')).toBe('/my-libraries')
  })

  it('says so when it is empty', async () => {
    fake.regions = { residenceRegionIds: [], workRegionIds: [] }
    fake.libraryIds = []
    renderSearch()
    await openMyLibrary()

    expect(await screen.findByText(/내 도서관이 비어 있어요/)).toBeTruthy()
  })

  it('a failed read offers 다시 시도 and searches 전체', async () => {
    fake.librariesStatus = 500
    renderSearch()
    await openMyLibrary()

    expect(await screen.findByText(/내 도서관을 불러오지 못했어요/)).toBeTruthy()
    await waitFor(() => expect(fake.books.length).toBeGreaterThan(0))
    expect(lastBooks().searchParams.has('scope')).toBe(false)
    expect(switchItem('전체 도서관').getAttribute('aria-checked')).toBe('true')

    fake.librariesStatus = 200
    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(screen.queryByText(/불러오지 못했어요/)).toBeNull())
  })

  it('shows 불러오는 중 while reading, searches 전체 meanwhile, then once more with 내 도서관 (step 10.5)', async () => {
    fake.holdLibraries = () => {}
    renderSearch()
    await openMyLibrary()

    expect(await screen.findByText('불러오는 중')).toBeTruthy()
    await waitFor(() => expect(fake.books.length).toBe(1))
    expect(fake.books[0].searchParams.has('scope')).toBe(false)
    expect(fake.books[0].searchParams.has('libraryIds')).toBe(false)

    fake.holdLibraries!()
    await waitFor(() => expect(lastBooks().searchParams.get('scope')).toBe('joinable'))
    expect(lastBooks().searchParams.get('libraryIds')).toBe('12,207,3')
    expect(lastBooks().searchParams.get('residenceRegionIds')).toBe('1114')
  })
})

describe('the switch after login', () => {
  it('reads 전체 도서관 | 내 도서관 and starts on 내 도서관 when there is one', async () => {
    renderSearch()

    await waitFor(() => expect(switchItem('내 도서관').getAttribute('aria-checked')).toBe('true'))
    await waitFor(() => expect(lastBooks().searchParams.get('scope')).toBe('joinable'))
  })

  it('sends the added libraries with both values (AC-6)', async () => {
    renderSearch()
    await waitFor(() => expect(lastBooks().searchParams.get('scope')).toBe('joinable'))

    await userEvent.setup().click(switchItem('전체 도서관'))

    await waitFor(() => expect(lastBooks().searchParams.has('scope')).toBe(false))
    expect(lastBooks().searchParams.get('libraryIds')).toBe('12,207,3')
    expect(lastBooks().searchParams.get('workRegionIds')).toBe('1102')
  })

  it('keeps the chosen value in this browser across a reload (AC-16)', async () => {
    const first = renderSearch()
    await waitFor(() => expect(lastBooks().searchParams.get('scope')).toBe('joinable'))
    await userEvent.setup().click(switchItem('전체 도서관'))
    await waitFor(() => expect(lastBooks().searchParams.has('scope')).toBe(false))
    expect(JSON.parse(localStorage.getItem(SETTINGS_STORAGE_KEY)!).myScope).toBe('all')
    first.unmount()

    renderSearch()

    await waitFor(() => expect(switchItem('내 도서관')).toBeTruthy())
    expect(switchItem('전체 도서관').getAttribute('aria-checked')).toBe('true')
    expect(lastBooks().searchParams.has('scope')).toBe(false)
  })

  it('empty 내 도서관: starts on 전체; 내 도서관 is joinable without libraryIds (AC-7)', async () => {
    fake.regions = { residenceRegionIds: [], workRegionIds: [] }
    fake.libraryIds = []
    renderSearch()
    await openMyLibrary()
    await screen.findByText(/내 도서관이 비어 있어요/)
    await userEvent.setup().keyboard('{Escape}')
    expect(switchItem('전체 도서관').getAttribute('aria-checked')).toBe('true')

    await userEvent.setup().click(switchItem('내 도서관'))

    await waitFor(() => expect(lastBooks().searchParams.get('scope')).toBe('joinable'))
    expect(lastBooks().searchParams.has('libraryIds')).toBe(false)
    expect(lastBooks().searchParams.has('residenceRegionIds')).toBe(false)
  })
})

describe('before login (AC-14)', () => {
  it('keeps the cycle 05 line, switch and request', async () => {
    fake.signedIn = false
    localStorage.setItem('chaekcheck.regions.v1', JSON.stringify({ residence: [1114], work: [] }))
    renderSearch('/search?title=사랑&view=books&scope=joinable')

    await waitFor(() => expect(fake.books.length).toBeGreaterThan(0))
    expect(switchItem('가입 가능한 곳').getAttribute('aria-checked')).toBe('true')
    expect(screen.queryByText('내 도서관')).toBeNull()
    expect(screen.getByText('내 지역')).toBeTruthy()
    expect(lastBooks().searchParams.get('scope')).toBe('joinable')
    expect(lastBooks().searchParams.get('residenceRegionIds')).toBe('1114')
    expect(lastBooks().searchParams.has('libraryIds')).toBe(false)
  })
})

describe('phones: "찾을 도서관" inside the settings panel (D안, user choice 2026-10-05)', () => {
  function phoneScreen() {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query.includes('640px'),
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }))
  }

  it('hides the switch line and shows it in the settings panel, with 내 도서관 관리 to S10', async () => {
    phoneScreen()
    renderSearch()

    await waitFor(() => expect(lastBooks().searchParams.get('scope')).toBe('joinable'))
    expect(screen.queryByRole('radio', { name: '내 도서관' })).toBeNull()
    expect(screen.getByRole('button', { name: '검색 설정' })).toBeTruthy()

    await userEvent.setup().click(screen.getByRole('button', { name: '검색 설정' }))

    expect(await screen.findByText('찾을 도서관')).toBeTruthy()
    expect(switchItem('내 도서관').getAttribute('aria-checked')).toBe('true')
    expect(screen.getByRole('link', { name: '내 도서관 관리' }).getAttribute('href')).toBe('/my-libraries')
  })

  it('marks the settings button when the scope is not the default', async () => {
    phoneScreen()
    renderSearch('/search?title=사랑&view=books&visit=none')

    expect(await screen.findByRole('button', { name: '검색 설정 (찾을 도서관 바꿈)' })).toBeTruthy()
  })
})
