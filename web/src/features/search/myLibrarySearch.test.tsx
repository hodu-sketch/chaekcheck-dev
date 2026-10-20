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
import { exampleOf, pickExample, SearchPage } from './SearchPage'
import { START_EXAMPLES } from './startExamples'
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
  /** `GET /api/v1/libraries` calls (the box's names, D4). */
  libraryLists: number
  /** The 시도 `GET /api/v1/regions/guess` names for the visitor's address (the example card, user 2026-10-20). */
  sido: string | null
}

let fake: Fake

function fakeServer() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/v1/me') return fake.signedIn ? json(200, { id: 7, loginMethods: ['KAKAO'], consents: [] }) : json(401, {})
      if (url === '/api/v1/auth/refresh') return json(401, {})
      if (url === '/api/v1/regions') return json(200, TREE)
      if (url === '/api/v1/regions/guess') return json(200, { sido: fake.sido })
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
      if (url === '/api/v1/libraries') {
        fake.libraryLists += 1
        return json(200, {
          libraries: [
            { id: 12, name: '마포구립 전자도서관', eligibility: 'ELIGIBLE', matchedBy: [] },
            { id: 207, name: '울산동부도서관', eligibility: 'OPEN', matchedBy: [] },
          ],
        })
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

// the address [검색] writes: visit=none, the switch being on by default (user 2026-10-20); the same address, so a
// [검색] with nothing changed stays on it
function renderSearch(address = '/search?visit=none&title=사랑&view=books') {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <NoticeProvider>
          <MyLibraryProvider>
            <RegionProvider>
              <MemoryRouter initialEntries={[address]}>
                <Routes>
                  <Route path="/" element={<SearchPage />} />
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
    libraryLists: 0,
    sido: null,
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

/**
 * The results' line with the my page link (user 2026-10-20: "검색창, 추천도서 창에서 지역 선택하는 거 싹 다 빼고 … 마이페이지
 * 이동 링크"); no region box there any more.
 */
const noteOf = async () =>
  (await screen.findByRole('link', { name: '마이페이지' }, { timeout: 5000 })).closest('p')!.textContent
const NOTE_NONE = '누구나 가입할 수 있는 도서관에서만 찾고 있어요. 더 많은 도서관에서 찾으려면 마이페이지에서 지역과 도서관을 골라 주세요'
/** The example regions the first screen may pick: two places or more, one besides the region's own. */
const pickable = START_EXAMPLES.filter(
  (one) => one.places.length >= 2 && one.places.some((place) => place !== one.place.split(' ').slice(1).join(' ')),
)
const noBox = () => {
  expect(screen.queryByRole('button', { name: /^지역 (선택|설정)$/ })).toBeNull()
  expect(screen.queryByRole('region', { name: '찾을 도서관' })).toBeNull()
}

describe('S1 "내 도서관" line (cycle 22 R1, R3)', () => {
  // no line at all with regions (user 2026-10-20: "서울 강남구에 살아요 · 마이페이지에서 바꿀 수 있어요 이거 문구 자체를 빼줘")
  it('with regions: no line, no box and no list read (user 2026-10-20)', async () => {
    renderSearch()
    await waitFor(() => expect(fake.books).toHaveLength(1))
    await act(async () => {})
    expect(screen.queryByText(/에 살아요/)).toBeNull()
    expect(screen.queryByRole('link', { name: '마이페이지' })).toBeNull()
    noBox()
    expect(fake.libraryLists).toBe(0)
  })

  it('empty after login: only libraries anyone may join, and the my page to pick a region', async () => {
    fake.regions = { residenceRegionIds: [], workRegionIds: [] }
    fake.libraryIds = []
    renderSearch()

    await waitFor(async () => expect(await noteOf()).toBe(NOTE_NONE))
    noBox()
  })

  it('a failed read offers 다시 시도 and searches joinable without regions; a read that works searches again', async () => {
    fake.librariesStatus = 500
    renderSearch()

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

  it('sends no search while reading and shows no line, then searches with 내 도서관 (cycle 22 D7)', async () => {
    fake.holdLibraries = () => {}
    renderSearch()
    await act(async () => {})
    expect(fake.books).toHaveLength(0)
    expect(screen.queryByRole('link', { name: '마이페이지' })).toBeNull()

    fake.holdLibraries!()
    await waitFor(() => expect(fake.books).toHaveLength(1))
    expect(lastBooks().searchParams.get('scope')).toBe('joinable')
    expect(lastBooks().searchParams.get('libraryIds')).toBe('12,207,3')
    expect(lastBooks().searchParams.get('residenceRegionIds')).toBe('1114')
    expect(screen.queryByRole('link', { name: '마이페이지' })).toBeNull()
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
    await waitFor(async () => expect(await noteOf()).toBe(NOTE_NONE))

    await waitFor(() => expect(lastBooks().searchParams.get('scope')).toBe('joinable'))
    expect(lastBooks().searchParams.has('libraryIds')).toBe(false)
    expect(lastBooks().searchParams.has('residenceRegionIds')).toBe(false)
  })
})

describe('before login (AC-14, cycle 07 flow S1)', () => {
  it("no switch and no line; this browser's regions are sent", async () => {
    fake.signedIn = false
    localStorage.setItem('chaekcheck.regions.v1', JSON.stringify({ residence: [1114], work: [] }))
    renderSearch('/search?title=사랑&view=books&scope=joinable')

    await waitFor(() => expect(fake.books.length).toBeGreaterThan(0))
    expect(screen.queryAllByRole('radio')).toEqual([])
    expect(screen.queryByText(/에 살아요/)).toBeNull()
    noBox()
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

  // the switch is on by default and on the my page since user 2026-10-20; the results read the address only
  it('the results read 온라인 가입만 from the address; the kept switch differs: the line asks for [검색], which writes it', async () => {
    fake.signedIn = false
    localStorage.setItem('chaekcheck.regions.v1', JSON.stringify({ residence: [1114], work: [] }))
    renderSearch('/search?title=사랑&view=books')
    await waitFor(() => expect(fake.books).toHaveLength(1))
    expect(lastBooks().searchParams.has('visit')).toBe(false)
    expect(screen.queryByRole('switch')).toBeNull()
    const hint = await screen.findByRole('status')
    expect(hint.textContent).toBe('가입 방법을 바꿨어요. 바뀐 조건으로 찾으려면 [검색]을 눌러 주세요')

    await userEvent.setup().click(within(hint).getByRole('button', { name: '[검색]' }))
    await waitFor(() => expect(lastBooks().searchParams.get('visit')).toBe('none'))
    await waitFor(() => expect(screen.queryByText(/가입 방법을 바꿨어요/)).toBeNull())
    cleanup()

    // the switch turned off on the my page: an address with visit=none still searches with it
    localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify({ visit: 'any' }))
    fake.books = []
    renderSearch('/search?title=사랑&view=books&visit=none')
    await waitFor(() => expect(fake.books.length).toBeGreaterThan(0))
    expect(lastBooks().searchParams.get('visit')).toBe('none')
    expect((await screen.findByRole('status')).textContent).toMatch(/^가입 방법을 바꿨어요/)
  })

  it('without regions or added libraries: no box, the line points to the my page', async () => {
    fake.signedIn = false
    renderSearch('/search?title=사랑&view=books')

    await waitFor(() => expect(fake.books.length).toBeGreaterThan(0))
    await waitFor(async () => expect(await noteOf()).toBe(NOTE_NONE))
    expect(screen.getByRole('link', { name: '마이페이지' }).getAttribute('href')).toBe('/my-libraries')
    noBox()
    // no names to read without added libraries (D4)
    expect(fake.libraryLists).toBe(0)
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

describe('phones: the chip and its own "찾을 도서관" sheet (cycle 22 R2, user 2026-10-19)', () => {
  function phoneScreen() {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query.includes('640px'),
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }))
  }

  // the results have no chip since user 2026-10-20: the line with the my page link under the search box
  it('results: no chip and no sheet; the line with the my page link without regions', async () => {
    phoneScreen()
    renderSearch()

    await waitFor(() => expect(lastBooks().searchParams.get('scope')).toBe('joinable'))
    await act(async () => {})
    expect(screen.queryByRole('link', { name: '마이페이지' })).toBeNull()
    cleanup()

    fake.regions = { residenceRegionIds: [], workRegionIds: [] }
    fake.libraryIds = []
    renderSearch()
    await waitFor(async () => expect(await noteOf()).toBe(NOTE_NONE))
    expect(screen.getByRole('button', { name: '검색 설정' })).toBeTruthy()
    noBox()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('the main page: the chip opens its own "찾을 도서관" sheet with 거주 and the my page line only', async () => {
    phoneScreen()
    renderSearch('/')

    const chip = await screen.findByRole('button', { name: '지역을 선택해주세요' })
    const user = userEvent.setup()
    await user.click(chip)
    const sheet = within(await screen.findByRole('dialog', { name: '찾을 도서관' }))
    expect(sheet.getByTestId('sheet-handle')).toBeTruthy()
    expect(sheet.queryByRole('button', { name: /^보기 방식/ })).toBeNull()
    expect(sheet.getByRole('heading', { name: '거주' })).toBeTruthy()
    expect(sheet.queryByRole('heading', { name: '일하는 곳' })).toBeNull()
    expect(sheet.queryByRole('switch')).toBeNull()
    expect(sheet.getByRole('link', { name: '마이페이지' }).getAttribute('href')).toBe('/my-libraries')

    await user.click(sheet.getByRole('button', { name: '닫기' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('the ⚙ sheet has no "찾을 도서관" and no 방문 가입 switch', async () => {
    phoneScreen()
    renderSearch()

    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: '검색 설정' }))
    const sheet = within(await screen.findByRole('dialog', { name: '검색 설정' }))
    expect(sheet.getByRole('button', { name: /^보기 방식/ }).textContent).toBe('보기 방식책 먼저 고르기')
    expect(sheet.queryByText('찾을 도서관')).toBeNull()
    expect(sheet.queryByRole('switch', { name: '온라인에서 가입 가능한 곳만 보기' })).toBeNull()
  })
})

/** User report 2026-10-14: words typed but not searched yet went away when a switch was pressed. */
describe('typed words stay on the search screen', () => {
  // the switch is on the my page since user 2026-10-20; a region saved meanwhile leaves the words too
  it('through a region saved meanwhile, and the next search keeps the sort', async () => {
    renderSearch('/search?title=사랑&view=books&visit=none&sort=newest')
    await waitFor(() => expect(fake.books.length).toBeGreaterThan(0))
    const user = userEvent.setup()
    const title = screen.getByRole('searchbox', { name: '책 제목' })

    await user.clear(title)
    await user.type(title, '채식주의자')
    await user.click(screen.getByRole('button', { name: 'save region' }))
    await waitFor(() => expect(fake.regions.residenceRegionIds).toEqual([4110]))
    expect((screen.getByRole('searchbox', { name: '책 제목' }) as HTMLInputElement).value).toBe('채식주의자')

    await user.click(searchButton())
    await waitFor(() => expect(lastBooks().searchParams.get('title')).toBe('채식주의자'))
    expect(lastBooks().searchParams.get('sort')).toBe('newest')
    expect(lastBooks().searchParams.get('visit')).toBe('none')
    expect(lastBooks().searchParams.get('residenceRegionIds')).toBe('4110')
  })
})


/** 「전자책 검색」 (header 도서검색, user 2026-10-16, design A): /search before a search keeps the results' layout. */
describe('전자책 검색', () => {
  it('shows its name and a short note, not the first screen headline, and sends no search', async () => {
    renderSearch('/search')
    expect(await screen.findByRole('heading', { level: 1, name: '전자책 검색' })).toBeTruthy()
    expect(screen.getByText('책 제목을 넣으면, 그 책을 지금 빌릴 수 있는 전자도서관을 여기에 보여 드려요')).toBeTruthy()
    expect(screen.queryByText('사기 전에 빌릴 수 있는 전자도서관부터')).toBeNull()
    expect(screen.queryByRole('complementary', { name: /에 살면$/ })).toBeNull()
    expect(fake.books).toHaveLength(0)
  })

  it('has the 가입 방법 chip before a search too: it only sets the next search (user 2026-10-20)', async () => {
    const user = userEvent.setup()
    renderSearch('/search')
    await user.click(await screen.findByRole('button', { name: '온라인 가입만' }))
    expect(await screen.findByRole('button', { name: '방문 가입도' })).toBeTruthy()
    expect(JSON.parse(localStorage.getItem(SETTINGS_STORAGE_KEY)!).visit).toBe('any')
    expect(fake.books).toHaveLength(0)
  })
})

/** The first screen (user 2026-10-20, ADR-0139, handoff 5-A-1·2, main screen (나)): `/` before a search. */
describe('the first screen', () => {
  const QUESTION = '전자책을 빌릴 수 있는 전자도서관에서 찾아 드릴게요.'
  // the line above the search box (its 빌릴 수 있는 in its own span)
  const question = () => screen.getByText((_, element) => element?.tagName === 'P' && element.textContent === QUESTION)
  const example = () => screen.queryByRole('complementary', { name: /에 살면$/ })
  const rows = () =>
    within(example()!)
      .getAllByRole('listitem')
      .map((item) => item.textContent)

  it('wider screens: eyebrow, the question headline, the two lines with 자세히, the question; no search', async () => {
    renderSearch('/')
    const headline = await screen.findByRole('heading', { level: 1 })
    expect(headline.textContent).toBe('읽고 싶은 전자책,사기 전에전자도서관부터')
    expect(headline.querySelectorAll('br')).toHaveLength(2)
    expect(screen.getByText('공공 전자도서관은 무료로 빌릴 수 있어요')).toBeTruthy()
    const lead = screen.getByText(/에 살아도/).closest('p')!
    expect(lead.textContent).toMatch(
      /^\S+에 살아도 .+ 전자도서관에서 전자책을 빌릴 수 있어요\. 자세히 →$/,
    )
    expect(screen.getByRole('link', { name: '자세히 →' }).getAttribute('href')).toBe('/about')
    // 빌릴 수 있는 in purple (user 2026-10-20)
    expect(question().querySelector('span span')?.textContent).toBe('빌릴 수 있는')
    expect(screen.queryByRole('complementary', { name: '이렇게 이용해 보세요' })).toBeNull()
    expect(screen.getByRole('button', { name: '검색' })).toBeTruthy()
    expect(screen.getByRole('search').hasAttribute('data-start')).toBe(true)
    // the condition line with this account's regions
    await screen.findByRole('button', { name: '지역을 선택해주세요' })
    // the lead names the person's own residence, not the random example (user 2026-10-20)
    await waitFor(() => expect(lead.textContent).toMatch(/^마포구에 살아도 /))
    // this account's 거주 has an example: the card of it, with its 직장 as the 재직 pill (user 2026-10-20; its numbers
    // before)
    await waitFor(() => expect(example()).not.toBeNull())
    expect(within(example()!).getByRole('heading', { level: 2 }).textContent).toBe('서울 마포구에 살면')
    // 「일하는 곳」 even with a workplace set (user 2026-10-20: "그냥 일하는곳 + 로")
    await waitFor(() => expect(rows()[1]).toBe('재직일하는 곳의 도서관더하면'))
    expect(rows()[0]).toMatch(/^거주마포구/)
    // the lead names two of the card's other places
    const shown = within(example()!).getAllByRole('listitem')[0].textContent!
    for (const other of lead.textContent!.replace(/^마포구에 살아도 /, '').split(' ')[0].split('·')) {
      expect(shown).toContain(other)
    }
    expect(fake.books).toHaveLength(0)
  })

  // MyNumbers only when the person's region has no example, or only workplaces are set (user 2026-10-20)
  it('only work regions: a 재직 card of the workplace, and the lead says 일해도, no numbers (user 2026-10-20)', async () => {
    fake.regions = { residenceRegionIds: [], workRegionIds: [1102] }
    renderSearch('/')
    await screen.findByRole('button', { name: '지역을 선택해주세요' })
    const card = await screen.findByRole('complementary', { name: '서울 중구에서 일하면' })
    const lines = within(card)
      .getAllByRole('listitem')
      .map((item) => item.textContent)
    expect(lines[0]).toBe('거주사는 곳의 도서관더하면')
    expect(lines[1]).toMatch(/^재직/)
    expect(screen.getByText(/^중구에서 일해도 /)).toBeTruthy()
    expect(screen.queryByText(/가입할 수 있는 도서관 \d+곳/)).toBeNull()
  })

  it('without regions: a random region in the card and the lead, 거주 · 재직 · 상관없이, no 예: (user 2026-10-20)', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0)
    fake.signedIn = false
    renderSearch('/')
    await screen.findByRole('heading', { level: 1 })
    await waitFor(() => expect(example()).not.toBeNull())
    // no 시도 from the address: a 서울 example (user 2026-10-20: "대충 모르겠으면 서울쪽으로")
    const card = pickExample(null, () => 0)
    expect(card.place).toMatch(/^서울 /)
    const data = START_EXAMPLES.find((one) => one.place === card.place)!
    expect(screen.getByText(/에 살아도/).textContent).toBe(
      `${card.home}에 살아도 ${card.others.join('·')} 전자도서관에서 전자책을 빌릴 수 있어요.`,
    )
    expect(within(example()!).getByRole('heading', { level: 2 }).textContent).toBe(`${card.place}에 살면`)
    expect(rows()).toEqual([
      `거주${card.residence.join('')}+ ${data.eligible - card.residence.length}곳`,
      '재직일하는 곳의 도서관더하면',
      `상관없이${card.open.join('')}+ ${data.open - 3}곳`,
    ])
    expect(within(example()!).queryByText(/방문 가입/)).toBeNull()
    vi.restoreAllMocks()
  })

  // the visitor's 시도 first, 서울 when not known; every region of the country (user 2026-10-20: "전국 넣어줘")
  it('picks a region of the given 시도, any region of it; the own place first, numbers from the list', () => {
    const sidos = [...new Set(START_EXAMPLES.map((one) => one.place.split(' ')[0]))]
    expect(START_EXAMPLES.length).toBeGreaterThanOrEqual(230)
    for (const sido of [...sidos, null, '없는곳']) {
      for (let i = 0; i < 20; i += 1) {
        const any = pickExample(sido)
        const data = START_EXAMPLES.find((one) => one.id === any.id)!
        expect(data.place.split(' ')[0]).toBe(sido && sidos.includes(sido) ? sido : '서울')
        expect(any.residence.length + any.residenceRest).toBe(data.eligible)
        expect(any.residence.slice(0, 3)).toEqual(expect.arrayContaining(any.others))
      }
    }
  })

  it('exampleOf: found by the region id, named as the screens name it (전남광주 → 전남, 세종특별자치시)', () => {
    const known = pickable[0]
    expect(exampleOf(known.id, () => 0)?.place).toBe(known.place)
    const gwangyang = START_EXAMPLES.find((one) => one.place === '전남광주 광양시')!
    expect(exampleOf(gwangyang.id, () => 0)?.place).toBe('전남 광양시')
    const sejong = START_EXAMPLES.find((one) => one.place.startsWith('세종 '))!
    expect(exampleOf(sejong.id, () => 0)?.place).toBe('세종특별자치시')
    expect(exampleOf(-1, () => 0)).toBeNull()
  })

  it('asks the 시도 of the address and shows an example of it', async () => {
    fake.signedIn = false
    fake.sido = '부산'
    renderSearch('/')
    await screen.findByRole('heading', { level: 1 })
    await waitFor(() => expect(example()).not.toBeNull())
    expect(within(example()!).getByRole('heading', { level: 2 }).textContent).toMatch(/^부산 /)
  })

  it('a failed 시도 ask still shows a 서울 example', async () => {
    fake.signedIn = false
    vi.mocked(fetch).mockImplementationOnce(async () => json(500, {}))
    renderSearch('/')
    await waitFor(() => expect(example()).not.toBeNull())
    expect(within(example()!).getByRole('heading', { level: 2 }).textContent).toMatch(/^서울 /)
  })

  it('phones: the same two lines without 자세히; the example card after the search box, three pills a row', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0)
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query.includes('640px'),
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }))
    fake.signedIn = false
    renderSearch('/')
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getByText(/에 살아도/).closest('p')!.textContent).toMatch(/전자책을 빌릴 수 있어요\.$/)
    expect(screen.queryByRole('link', { name: '자세히 →' })).toBeNull()
    const asked = question()
    expect(asked.compareDocumentPosition(screen.getByRole('search')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    await waitFor(() => expect(example()).not.toBeNull())
    expect(screen.getByRole('search').compareDocumentPosition(example()!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    const card = pickExample(null, () => 0)
    const data = START_EXAMPLES.find((one) => one.place === card.place)!
    const shown = card.residence.slice(0, 3)
    expect(rows()).toEqual([
      `거주${shown.join('')}+ ${data.eligible - shown.length}`,
      '재직일하는 곳+',
      `상관없이${card.open.join('')}+ ${data.open - 3}`,
    ])
    vi.restoreAllMocks()
  })

  it('wider screens: the box opens under the card in the page, no window; ⓧ and Esc close it (user 2026-10-20)', async () => {
    fake.signedIn = false
    renderSearch('/')
    const user = userEvent.setup()
    const button = await screen.findByRole('button', { name: '지역을 선택해주세요' })
    await user.click(button)
    expect(screen.queryByRole('dialog')).toBeNull()
    const panel = screen.getByRole('region', { name: '찾을 도서관' })
    expect(button.getAttribute('aria-expanded')).toBe('true')
    expect(await within(panel).findByRole('button', { name: '사는 곳 추가' })).toBeTruthy()
    // only 거주 and a line to the my page (user 2026-10-20)
    expect(within(panel).getAllByRole('heading').map((heading) => heading.textContent)).toEqual(['거주'])
    expect(within(panel).queryByText(/^직접 추가/)).toBeNull()
    expect(within(panel).getByRole('link', { name: '마이페이지' }).getAttribute('href')).toBe('/my-libraries')
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('region', { name: '찾을 도서관' })).toBeNull()
    await user.click(button)
    await user.click(within(screen.getByRole('region', { name: '찾을 도서관' })).getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('region', { name: '찾을 도서관' })).toBeNull()
  })

  // the main page's button always opens the box, showing the first 거주 only; the rest stays (user 2026-10-20)
  it('with work regions and added libraries: the box still opens and shows only the first 거주', async () => {
    renderSearch('/')
    const user = userEvent.setup()
    const button = await screen.findByRole('button', { name: '지역을 선택해주세요' })
    await user.click(button)
    const panel = screen.getByRole('region', { name: '찾을 도서관' })
    const residence = within(panel).getByRole('group', { name: '거주' })
    await waitFor(() =>
      expect(within(residence).getByRole('list', { name: '고른 사는 곳' }).textContent).toBe('서울 마포구'),
    )
    expect(within(panel).queryByText('서울 중구')).toBeNull()
  })

  it('after a search: no headline and no steps card', async () => {
    renderSearch('/search?title=사랑&view=books')
    await waitFor(() => expect(fake.books).toHaveLength(1))
    expect(screen.queryByRole('heading', { level: 1 })).toBeNull()
    expect(screen.queryByRole('complementary', { name: /에 살면$/ })).toBeNull()
    expect(screen.queryByText((_, element) => element?.tagName === 'P' && element.textContent === QUESTION)).toBeNull()
  })
})
