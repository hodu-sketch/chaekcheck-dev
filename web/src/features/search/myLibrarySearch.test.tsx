import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthContext'
import { NoticeProvider } from '../auth/NoticeContext'
import { MyLibraryProvider } from '../mylibrary/MyLibraryContext'
import { REGION_IMPORT_KEY } from '../mylibrary/regionImport'
import { RegionProvider, useRegions } from '../region/RegionContext'
import { SearchPage } from './SearchPage'
import { SETTINGS_STORAGE_KEY } from './searchSettings'

/**
 * Cycle 06 step 10 on the search screen with a fake server: the "내 도서관" line and what the book search sends before
 * and after login (AC-6, AC-7, AC-14). Cycle 22 step 1: no scope switch (R4-1), the search keeps the values it was
 * started with (D6, AC-20) and waits for the account (D7, AC-21). 책 먼저 고르기 (`view=books`) keeps it to
 * `/api/v1/books`.
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
    vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/v1/me') return fake.signedIn ? json(200, { id: 7, loginMethods: ['KAKAO'], consents: [] }) : json(401, {})
      if (url === '/api/v1/auth/refresh') return json(401, {})
      if (url === '/api/v1/regions') return json(200, TREE)
      if (url.startsWith('/api/v1/regions/resolutions?ids=')) {
        const ids = url.split('=')[1].split(',').map(Number)
        return json(200, ids.map((id) => ({ id, status: 'ACTIVE', label: null, replacements: [] })))
      }
      if (url === '/api/v1/me/regions' && init?.method === 'PUT') {
        fake.regions = JSON.parse(init.body as string)
        return json(200, fake.regions)
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

/** Stands in for the region box (step 2): saves the regions only; and the browser's back button. */
function Controls() {
  const { setRegions } = useRegions()
  const navigate = useNavigate()
  return (
    <>
      <button type="button" onClick={() => void setRegions({ residence: [4110], work: [] })}>
        save region
      </button>
      <button type="button" onClick={() => void navigate(-1)}>
        back
      </button>
    </>
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
                <Controls />
              </MemoryRouter>
            </RegionProvider>
          </MyLibraryProvider>
        </NoticeProvider>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

const lastBooks = () => fake.books.at(-1)!
const searchButton = () => screen.getByRole('button', { name: '검색' })

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

  it('a failed read offers 다시 시도 and searches joinable without regions; a read that works searches again', async () => {
    fake.librariesStatus = 500
    renderSearch()
    await openMyLibrary()

    expect(await screen.findByText(/내 도서관을 불러오지 못했어요/)).toBeTruthy()
    await waitFor(() => expect(fake.books.length).toBe(1))
    expect(lastBooks().searchParams.get('scope')).toBe('joinable')
    expect(lastBooks().searchParams.has('libraryIds')).toBe(false)
    expect(lastBooks().searchParams.has('residenceRegionIds')).toBe(false)

    fake.librariesStatus = 200
    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(screen.queryByText(/불러오지 못했어요/)).toBeNull())
    await waitFor(() => expect(lastBooks().searchParams.get('libraryIds')).toBe('12,207,3'))
    expect(lastBooks().searchParams.get('residenceRegionIds')).toBe('1114')
  })

  it('shows 불러오는 중 and sends no search while reading, then one with 내 도서관 (cycle 22 D7)', async () => {
    fake.holdLibraries = () => {}
    renderSearch()
    await openMyLibrary()

    expect(await screen.findByText('불러오는 중')).toBeTruthy()
    await act(async () => {})
    expect(fake.books).toHaveLength(0)

    fake.holdLibraries!()
    await waitFor(() => expect(fake.books).toHaveLength(1))
    expect(lastBooks().searchParams.get('scope')).toBe('joinable')
    expect(lastBooks().searchParams.get('libraryIds')).toBe('12,207,3')
    expect(lastBooks().searchParams.get('residenceRegionIds')).toBe('1114')
  })
})

describe('no scope switch (cycle 22 R4-1, AC-10)', () => {
  it('after login: no switch, joinable with the account regions and added libraries (AC-6)', async () => {
    renderSearch()

    await waitFor(() => expect(fake.books.length).toBeGreaterThan(0))
    expect(screen.queryAllByRole('radio')).toEqual([])
    expect(screen.queryByText('전체 도서관')).toBeNull()
    expect(lastBooks().searchParams.get('scope')).toBe('joinable')
    expect(lastBooks().searchParams.get('libraryIds')).toBe('12,207,3')
    expect(lastBooks().searchParams.get('workRegionIds')).toBe('1102')
  })

  it('ignores and removes the switch value an earlier version kept', async () => {
    localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify({ myScope: 'all', guestScope: 'all' }))
    renderSearch()

    await waitFor(() => expect(fake.books.length).toBeGreaterThan(0))
    expect(lastBooks().searchParams.get('scope')).toBe('joinable')
    expect(JSON.parse(localStorage.getItem(SETTINGS_STORAGE_KEY)!)).toEqual({})
  })

  it('scope=all in the address searches all for that search; the next search is joinable again (D10)', async () => {
    renderSearch('/search?title=사랑&view=books&scope=all')
    await waitFor(() => expect(fake.books.length).toBeGreaterThan(0))
    expect(lastBooks().searchParams.has('scope')).toBe(false)

    await userEvent.setup().click(searchButton())
    await waitFor(() => expect(lastBooks().searchParams.get('scope')).toBe('joinable'))
  })

  it('empty 내 도서관: joinable without libraryIds (AC-7)', async () => {
    fake.regions = { residenceRegionIds: [], workRegionIds: [] }
    fake.libraryIds = []
    renderSearch()
    await openMyLibrary()
    await screen.findByText(/내 도서관이 비어 있어요/)

    await waitFor(() => expect(lastBooks().searchParams.get('scope')).toBe('joinable'))
    expect(lastBooks().searchParams.has('libraryIds')).toBe(false)
    expect(lastBooks().searchParams.has('residenceRegionIds')).toBe(false)
  })
})

describe('before login (AC-14, cycle 07 flow S1)', () => {
  it('no switch; the regions sit behind "내 도서관 관리"', async () => {
    fake.signedIn = false
    localStorage.setItem('chaekcheck.regions.v1', JSON.stringify({ residence: [1114], work: [] }))
    renderSearch('/search?title=사랑&view=books&scope=joinable')

    await waitFor(() => expect(fake.books.length).toBeGreaterThan(0))
    expect(screen.queryAllByRole('radio')).toEqual([])
    expect(screen.getByRole('button', { name: /내 도서관 관리/ })).toBeTruthy()
    expect(lastBooks().searchParams.get('scope')).toBe('joinable')
    expect(lastBooks().searchParams.get('residenceRegionIds')).toBe('1114')
    expect(lastBooks().searchParams.has('libraryIds')).toBe(false)
  })

  it('sends the libraries this browser added (AC-18)', async () => {
    fake.signedIn = false
    localStorage.setItem('chaekcheck.chosenLibraries.v1', JSON.stringify([281, 300]))
    renderSearch('/search?title=사랑&view=books')

    await waitFor(() => expect(fake.books.length).toBeGreaterThan(0))
    expect(lastBooks().searchParams.get('libraryIds')).toBe('281,300')
  })

  it('방문 없이 가입 is only kept; [검색] writes it into the address, and the results read the address', async () => {
    fake.signedIn = false
    const first = renderSearch('/search?title=사랑&view=books')
    await waitFor(() => expect(fake.books).toHaveLength(1))
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /방문 없이 가입/ }))
    await act(async () => {})
    expect(fake.books).toHaveLength(1)
    expect(JSON.parse(localStorage.getItem(SETTINGS_STORAGE_KEY)!).visit).toBe('none')

    await user.click(searchButton())
    await waitFor(() => expect(fake.books).toHaveLength(2))
    expect(lastBooks().searchParams.get('visit')).toBe('none')
    first.unmount()

    // a new visit: the switch shows the kept choice, the address without visit searches without it until [검색]
    fake.books = []
    renderSearch('/search?title=사랑&view=books')
    await waitFor(() => expect(fake.books.length).toBeGreaterThan(0))
    expect(lastBooks().searchParams.has('visit')).toBe(false)
    expect(screen.getByRole('button', { name: /방문 없이 가입/ }).getAttribute('aria-pressed')).toBe('true')
    cleanup()

    fake.books = []
    renderSearch('/search?title=사랑&view=books&visit=none')
    await waitFor(() => expect(fake.books.length).toBeGreaterThan(0))
    expect(lastBooks().searchParams.get('visit')).toBe('none')
  })

  it('without regions or added libraries the button is "내 도서관 설정" and goes to S10', async () => {
    fake.signedIn = false
    renderSearch('/search?title=사랑&view=books')

    await waitFor(() => expect(fake.books.length).toBeGreaterThan(0))
    expect(screen.getByRole('link', { name: '내 도서관 설정' }).getAttribute('href')).toBe('/my-libraries')
  })
})

/** Cycle 22 D6, AC-20: checking a region only saves it; the search runs with the values it was started with. */
describe('검색 당시 값 (searchedWith)', () => {
  async function changeRegion() {
    await userEvent.setup().click(screen.getByRole('button', { name: 'save region' }))
  }

  it('before login: a changed region sends no request; [검색] then uses it, even with the same words', async () => {
    fake.signedIn = false
    localStorage.setItem('chaekcheck.regions.v1', JSON.stringify({ residence: [1114], work: [] }))
    renderSearch()
    await waitFor(() => expect(fake.books).toHaveLength(1))
    expect(lastBooks().searchParams.get('residenceRegionIds')).toBe('1114')

    await changeRegion()
    await waitFor(() => expect(JSON.parse(localStorage.getItem('chaekcheck.regions.v1')!).residence).toEqual([4110]))
    await act(async () => {})
    expect(fake.books).toHaveLength(1)

    await userEvent.setup().click(searchButton())
    await waitFor(() => expect(fake.books).toHaveLength(2))
    expect(lastBooks().searchParams.get('residenceRegionIds')).toBe('4110')
    expect(lastBooks().searchParams.get('title')).toBe('사랑')
  })

  it('after login: the account save sends no search; [검색] then uses the new region', async () => {
    document.cookie = 'XSRF-TOKEN=csrf; path=/'
    renderSearch()
    await waitFor(() => expect(fake.books).toHaveLength(1))

    await changeRegion()
    await waitFor(() => expect(fake.regions.residenceRegionIds).toEqual([4110]))
    await act(async () => {})
    expect(fake.books).toHaveLength(1)

    await userEvent.setup().click(searchButton())
    await waitFor(() => expect(fake.books).toHaveLength(2))
    expect(lastBooks().searchParams.get('residenceRegionIds')).toBe('4110')
    expect(lastBooks().searchParams.get('libraryIds')).toBe('12,207,3')
  })

  it('pressing [검색] again with nothing changed sends nothing new', async () => {
    renderSearch()
    await waitFor(() => expect(fake.books).toHaveLength(1))

    await userEvent.setup().click(searchButton())
    await act(async () => {})
    expect(fake.books).toHaveLength(1)
  })

  it('back to an earlier address searches with the last search values, not ones saved since', async () => {
    fake.signedIn = false
    localStorage.setItem('chaekcheck.regions.v1', JSON.stringify({ residence: [1114], work: [] }))
    renderSearch()
    await waitFor(() => expect(fake.books).toHaveLength(1))
    const user = userEvent.setup()

    const title = screen.getByRole('searchbox', { name: '책 제목' })
    await user.clear(title)
    await user.type(title, '채식주의자')
    await user.click(searchButton())
    await waitFor(() => expect(lastBooks().searchParams.get('title')).toBe('채식주의자'))

    await changeRegion()
    await user.click(screen.getByRole('button', { name: 'back' }))
    await waitFor(() => expect(lastBooks().searchParams.get('title')).toBe('사랑'))
    expect(lastBooks().searchParams.get('residenceRegionIds')).toBe('1114')
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

  it('hides the line and shows it in the settings panel, with 내 도서관 관리 to S10', async () => {
    phoneScreen()
    renderSearch()

    await waitFor(() => expect(lastBooks().searchParams.get('scope')).toBe('joinable'))
    expect(screen.queryByRole('button', { name: '방문 없이 가입' })).toBeNull()
    expect(screen.getByRole('button', { name: '검색 설정' })).toBeTruthy()

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '검색 설정' }))
    // three rows that open on a tap (user request 2026-10-05), each saying what it is set to
    const scopeRow = await screen.findByRole('button', { name: /^찾을 도서관/ })
    const sheet = within(screen.getByRole('dialog'))
    expect(scopeRow.textContent).toBe('찾을 도서관내 도서관')
    expect(sheet.getByRole('button', { name: /^보기 방식/ }).textContent).toBe('보기 방식책 먼저 고르기')
    expect(sheet.getByRole('button', { name: /^도서관에서 직접 찾기/ }).textContent).toBe('도서관에서 직접 찾기끔')
    await user.click(scopeRow)

    expect(screen.queryAllByRole('radio', { name: /도서관/ })).toEqual([])
    expect(screen.getByRole('button', { name: '방문 없이 가입' })).toBeTruthy()
    expect(screen.getByRole('link', { name: '내 도서관 관리' }).getAttribute('href')).toBe('/my-libraries')
  })

  it('marks the settings button when the search is not the default', async () => {
    phoneScreen()
    renderSearch('/search?title=사랑&view=books&scope=all')

    expect(await screen.findByRole('button', { name: '검색 설정 (찾을 도서관 바꿈)' })).toBeTruthy()
  })
})

/** User report 2026-10-14: words typed but not searched yet went away when a switch was pressed. */
describe('typed words stay on the search screen', () => {
  it('through "방문 없이 가입", and the next search keeps the sort', async () => {
    renderSearch('/search?title=사랑&view=books&sort=newest')
    await waitFor(() => expect(fake.books.length).toBeGreaterThan(0))
    const user = userEvent.setup()
    const title = screen.getByRole('searchbox', { name: '책 제목' })

    await user.clear(title)
    await user.type(title, '채식주의자')
    await user.click(screen.getByRole('button', { name: '방문 없이 가입' }))
    expect((screen.getByRole('searchbox', { name: '책 제목' }) as HTMLInputElement).value).toBe('채식주의자')

    await user.click(searchButton())
    await waitFor(() => expect(lastBooks().searchParams.get('title')).toBe('채식주의자'))
    expect(lastBooks().searchParams.get('sort')).toBe('newest')
    expect(lastBooks().searchParams.get('visit')).toBe('none')
  })
})


/** 「전자책 검색」 (header 도서검색, user 2026-10-16, design A): /search before a search keeps the results' layout. */
describe('전자책 검색', () => {
  it('shows its name and a short note, not the first screen headline, and sends no search', async () => {
    renderSearch('/search')
    expect(await screen.findByRole('heading', { level: 1, name: '전자책 검색' })).toBeTruthy()
    expect(screen.getByText('책 제목을 넣으면, 그 책을 지금 빌릴 수 있는 전자도서관을 여기에 보여 드려요')).toBeTruthy()
    expect(screen.queryByText('전자도서관을 찾아 드려요')).toBeNull()
    expect(fake.books).toHaveLength(0)
  })
})
