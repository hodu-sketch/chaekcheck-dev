import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchBooks } from '../../api/client'
import { holdingStream } from '../../api/searchStream'
import type { HoldingStreamEvent, TargetLibrary } from '../../api/types'
import { AuthProvider } from '../auth/AuthContext'
import { NoticeProvider } from '../auth/NoticeContext'
import { CHOSEN_LIBRARIES_KEY } from '../mylibrary/chosenLibraryStorage'
import { MyLibraryProvider } from '../mylibrary/MyLibraryContext'
import { RegionProvider } from '../region/RegionContext'
import { REGION_STORAGE_KEY } from '../region/regionStorage'
import { EMPTY_INPUT } from './searchCondition'
import { ADVANCED_TEXT, SearchForm } from './SearchForm'
import { SearchPage } from './SearchPage'
import { DEFAULT_SETTINGS, DIRECT_REASON, loadPrefs } from './searchSettings'

vi.mock('../../api/searchStream', () => ({
  holdingStream: vi.fn(),
  holdingBatchStream: vi.fn(),
  searchStream: vi.fn(),
  batchStream: vi.fn(),
}))
vi.mock('../../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/client')>()),
  fetchBooks: vi.fn(),
}))

function target(id: number, overrides: Partial<TargetLibrary> = {}): TargetLibrary {
  return {
    id,
    name: `도서관${id}`,
    libraryType: 'EBOOK',
    platformType: 'KYOBO_V1',
    priorityGroup: 'OPEN',
    deferred: false,
    extraConditionText: null,
    visitRequired: false,
    eligibility: 'OPEN',
    eligibilitySummary: null,
    joinMethod: null,
    joinUrl: null,
    joinCaution: null,
    joinNotice: null,
    homepageUrl: null,
    chosen: false,
    joinSurvey: 'SURVEYED',
    logoUrl: null,
    mobileSiteDiffers: false,
    ...overrides,
  }
}

const BOOK = {
  id: 7,
  title: '사랑의 기술',
  authorDisplay: '에리히 프롬',
  publisher: '문예출판사',
  publishedOn: '2019-08-30',
  holdingLibraryCount: 1,
  joinTier: 'OPEN' as const,
  exactTitle: false,
}

/** One library holding the book, borrowable now. */
function answer(onEvent: (event: HoldingStreamEvent) => void, libraries: TargetLibrary[] = [target(1)]) {
  const events: HoldingStreamEvent[] = [
    {
      name: 'targets',
      data: {
        filter: 'all',
        scope: 'joinable',
        visit: 'any',
        regionFilterApplied: false,
        libraries,
        holdings: [{ holdingId: 101, bookId: 7, libraryId: 1 }],
      },
    },
    {
      name: 'holding-result',
      data: {
        batchNo: 1,
        holdingId: 101,
        bookId: 7,
        libraryId: 1,
        item: {
          rank: 0,
          title: '사랑의 기술',
          author: null,
          publisher: null,
          supplier: null,
          holdings: 1,
          loaned: 0,
          reservations: 0,
          available: 1,
          titleKey: 'book:7',
          availability: 'AVAILABLE',
          availabilityBasis: 'COUNT',
          detailUrl: null,
        },
        fetchedAt: '',
        cached: false,
      },
    },
    { name: 'done', data: { batchNo: 1 } },
  ]
  for (const event of events) onEvent(event)
}

afterEach(cleanup)

beforeAll(() => {
  // Radix positions popovers with ResizeObserver, which jsdom does not have
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

const TREE = [
  { id: 11, name: '서울특별시', shortName: '서울', children: [{ id: 1168, name: '강남구' }, { id: 1150, name: '강서구' }] },
]

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/** Every request the screen sends, by path (the search streams are mocked apart). */
let requests: string[]

beforeEach(() => {
  requests = []
  vi.mocked(fetchBooks).mockReset()
  vi.mocked(holdingStream).mockReset()
  vi.mocked(fetchBooks).mockResolvedValue({
    items: [BOOK],
    page: 1,
    pageSize: 20,
    totalCount: 1,
    hasMore: false,
    collectedFrom: null,
    collectedTo: null,
  })
  vi.mocked(holdingStream).mockImplementation(async (_params, onEvent) => answer(onEvent))
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      requests.push(url)
      if (url === '/api/v1/me' || url === '/api/v1/auth/refresh') return json(401, {})
      if (url === '/api/v1/auth/providers') return json(200, ['KAKAO'])
      if (url === '/api/v1/regions') return json(200, TREE)
      if (url.startsWith('/api/v1/regions/resolutions?ids=')) {
        const ids = url.split('=')[1].split(',').map(Number)
        return json(200, ids.map((id) => ({ id, status: 'ACTIVE', label: null, replacements: [] })))
      }
      if (url === '/api/v1/usage-events') return new Response(null, { status: 204 })
      throw new Error(`unexpected ${url}`)
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})

function Providers({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <NoticeProvider>
          <MyLibraryProvider>
            <RegionProvider>{children}</RegionProvider>
          </MyLibraryProvider>
        </NoticeProvider>
      </AuthProvider>
    </QueryClientProvider>
  )
}

function setup(props: Partial<Parameters<typeof SearchForm>[0]> = {}) {
  const onSearch = vi.fn()
  const onSettingsChange = vi.fn()
  render(
    <Providers>
      <SearchForm
        initial={EMPTY_INPUT}
        settings={DEFAULT_SETTINGS}
        hasResults={false}
        onSearch={onSearch}
        onSettingsChange={onSettingsChange}
        {...props}
      />
    </Providers>,
  )
  return { onSearch, onSettingsChange, user: userEvent.setup() }
}

const titleBox = () => screen.getByRole('searchbox', { name: '책 제목' })
const moreButton = () => screen.getByRole('button', { name: '상세검색' })
const box = (name: string) => screen.getByRole('searchbox', { name }) as HTMLInputElement
const check = (name: string | RegExp) => screen.getByRole('checkbox', { name }) as HTMLInputElement

describe('SearchForm (cycle 04 AC-1)', () => {
  it('shows one title box, "상세검색" and settings, and no condition dropdown', () => {
    setup()

    expect(titleBox().getAttribute('placeholder')).toBe('책 제목을 입력하세요')
    expect(moreButton().getAttribute('aria-expanded')).toBe('false')
    expect(screen.getByRole('button', { name: '검색 설정' })).toBeTruthy()
    expect(screen.queryByRole('combobox')).toBeNull()
    expect(screen.queryByRole('searchbox', { name: '저자' })).toBeNull()
  })

  it('"상세검색" shows author and publisher; opening does not search [안]', async () => {
    const { user, onSearch } = setup()

    await user.click(moreButton())

    expect(box('저자')).toBeTruthy()
    expect(box('출판사')).toBeTruthy()
    expect(check('옮긴이').checked).toBe(false)
    expect(check('그림').checked).toBe(false)
    expect(check('엮은이').checked).toBe(false)
    expect(onSearch).not.toHaveBeenCalled()
  })

  it('searches every filled field, normalized; empty fields are left out', async () => {
    const { user, onSearch } = setup()

    await user.type(titleBox(), '  행동  ')
    await user.click(moreButton())
    await user.type(box('저자'), '새폴스키')
    await user.click(check(/완전 일치.*저자/))
    await user.click(check('옮긴이'))
    await user.click(screen.getByRole('button', { name: '검색' }))

    expect(onSearch).toHaveBeenCalledWith({
      title: '행동',
      author: '새폴스키',
      publisher: '',
      exact: ['author'],
      roles: ['translator'],
      sort: 'default',
    }, { all: false })
  })

  it('an author alone can be searched', async () => {
    const { user, onSearch } = setup()

    await user.click(moreButton())
    await user.type(box('저자'), '김달')
    await user.click(screen.getByRole('button', { name: '검색' }))

    expect(onSearch).toHaveBeenCalledWith(expect.objectContaining({ title: '', author: '김달' }), { all: false })
  })

  it('no filled field: "검색어를 입력하세요" and nothing is sent', async () => {
    const { user, onSearch } = setup()

    await user.click(screen.getByRole('button', { name: '검색' }))

    expect(screen.getByRole('alert').textContent).toContain('검색어를 입력하세요')
    expect(onSearch).not.toHaveBeenCalled()
  })

  it('a filled field shorter than 2 characters gets the cycle 01 message under it', async () => {
    const { user, onSearch } = setup()

    await user.type(titleBox(), '행동')
    await user.click(moreButton())
    await user.type(box('출판사'), '문')
    await user.click(screen.getByRole('button', { name: '검색' }))

    expect(screen.getByRole('alert').textContent).toContain('2글자 이상 입력하세요')
    expect(box('출판사').getAttribute('aria-invalid')).toBe('true')
    expect(onSearch).not.toHaveBeenCalled()
  })

  it('opens with the address conditions; with results a changed check searches again [안]', async () => {
    const initial = { ...EMPTY_INPUT, title: '행동', author: '새폴스키' }
    const { user, onSearch } = setup({ initial, hasResults: true })

    expect(moreButton().getAttribute('aria-expanded')).toBe('true')
    await user.click(check(/완전 일치.*제목/))

    expect(onSearch).toHaveBeenCalledWith(
      expect.objectContaining({ title: '행동', author: '새폴스키', exact: ['title'] }),
      { all: false },
    )
  })

  it('direct search dims what the site search cannot use, with the reason', async () => {
    const { user, onSearch } = setup({ settings: { view: 'instant', direct: true } })

    await user.click(moreButton())

    expect(screen.getByText(DIRECT_REASON)).toBeTruthy()
    expect(box('저자').disabled).toBe(true)
    expect(check(/완전 일치.*제목/).disabled).toBe(true)
    expect(check('옮긴이').disabled).toBe(true)
    await user.type(titleBox(), '사랑')
    await user.click(screen.getByRole('button', { name: '검색' }))
    expect(onSearch).toHaveBeenCalledWith(expect.objectContaining({ title: '사랑', author: '' }), { all: false })
  })

  it('settings change at once', async () => {
    const { user, onSettingsChange } = setup()

    await user.click(screen.getByRole('button', { name: '검색 설정' }))
    await user.click(screen.getByRole('radio', { name: /책 먼저 고르기/ }))
    expect(onSettingsChange).toHaveBeenLastCalledWith({ view: 'books', direct: false })

    await user.click(screen.getByRole('radio', { name: /^켬/ }))
    expect(onSettingsChange).toHaveBeenLastCalledWith({ view: 'instant', direct: true })
  })

  it('with direct search on, 책 먼저 고르기 cannot be chosen [안]', async () => {
    const { user } = setup({ settings: { view: 'instant', direct: true } })

    await user.click(screen.getByRole('button', { name: '검색 설정' }))

    expect((screen.getByRole('radio', { name: /책 먼저 고르기/ }) as HTMLButtonElement).disabled).toBe(true)
  })
})

/** Cycle 22 R2-1: on phones the "내 도서관" chip sits on its own line right under the title box. */
describe('the phone chip', () => {
  it('comes right after the title box, before 상세검색 and the settings', () => {
    setup({ chip: <button type="button">내 도서관: 서울 강남구 · 68곳</button> })
    const chip = screen.getByRole('button', { name: '내 도서관: 서울 강남구 · 68곳' })
    expect(titleBox().compareDocumentPosition(chip) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(chip.compareDocumentPosition(moreButton()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('the ⚙ sheet on phones has no "찾을 도서관" (the chip has its own sheet, user 2026-10-19)', async () => {
    const { user } = setup({ settingsSheet: true, chip: <button type="button">사는 곳·일하는 곳 고르기</button> })
    await user.click(screen.getByRole('button', { name: '검색 설정' }))
    const sheet = screen.getByRole('dialog', { name: '검색 설정' })
    expect(screen.getByTestId('sheet-handle')).toBeTruthy()
    expect(sheet.textContent).not.toContain('찾을 도서관')
  })

  it('is not there without one (wider screens)', () => {
    setup()
    expect(screen.queryByRole('button', { name: /^내 도서관/ })).toBeNull()
  })
})

const LIBRARIES = [
  { id: 1, name: '마포구립 전자도서관', eligibility: 'OPEN', matchedBy: [] },
  ...Array.from({ length: 11 }, (_, index) => ({
    id: 300 + index,
    name: `시립 ${String(index + 1).padStart(2, '0')} 전자도서관`,
    eligibility: 'OPEN',
    matchedBy: [],
  })),
]

/** The fetch stub answers the library list (`GET /api/v1/libraries`) too. */
function withLibraryList() {
  const base = vi.mocked(fetch).getMockImplementation()!
  vi.mocked(fetch).mockImplementation(async (input, init) => {
    if (input === '/api/v1/libraries') {
      requests.push(String(input))
      return json(200, { libraries: LIBRARIES })
    }
    return base(input, init)
  })
}

/** Cycle 22 R10, AC-12, AC-19, AC-20: the 상세검색 rows for the user's regions, libraries and the 전국 box. */
describe('상세검색: 사는 곳, 일하는 곳, 도서관, 범위', () => {
  it('has the four rows under 출판사', async () => {
    const { user } = setup()
    await user.click(moreButton())

    const publisher = box('출판사')
    const residence = screen.getByRole('group', { name: ADVANCED_TEXT.residence })
    const work = screen.getByRole('group', { name: ADVANCED_TEXT.work })
    const library = screen.getByRole('group', { name: ADVANCED_TEXT.library })
    const all = check(ADVANCED_TEXT.all)
    for (const [before, after] of [
      [publisher, residence],
      [residence, work],
      [work, library],
      [library, all],
    ]) {
      expect(before.compareDocumentPosition(after) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    }
    expect(screen.getByText(ADVANCED_TEXT.scope)).toBeTruthy()
    // 일하는 곳 waits behind its button until there is one
    expect(within(work).getByRole('button', { name: ADVANCED_TEXT.addWork })).toBeTruthy()
    expect(within(library).getByRole('searchbox', { name: ADVANCED_TEXT.nameSearch })).toBeTruthy()
    expect(within(library).getByText('직접 추가 0/20')).toBeTruthy()
    expect(all.checked).toBe(false)
    // D4: no library list without added libraries and without a name typed
    expect(requests).not.toContain('/api/v1/libraries')
  })

  it('a region check only saves: nothing is searched', async () => {
    const { user, onSearch } = setup()
    await user.click(moreButton())
    await user.click(await screen.findByRole('checkbox', { name: '강서구' }))

    expect(JSON.parse(localStorage.getItem(REGION_STORAGE_KEY)!).residence).toEqual([1150])
    expect(screen.getByRole('list', { name: '고른 사는 곳' }).textContent).toContain('강서구')
    expect(onSearch).not.toHaveBeenCalled()
  })

  it('finds libraries by name 10 at a time; [더하기] adds one, × takes it out', async () => {
    withLibraryList()
    const { user, onSearch } = setup()
    await user.click(moreButton())
    const library = screen.getByRole('group', { name: ADVANCED_TEXT.library })
    const input = within(library).getByRole('searchbox', { name: ADVANCED_TEXT.nameSearch })

    await user.type(input, '시립{Enter}')
    // Enter in the name box does not send the book search
    expect(onSearch).not.toHaveBeenCalled()
    const found = await within(library).findByRole('list', { name: '찾은 도서관' })
    expect(within(found).getAllByRole('listitem')).toHaveLength(10)
    await user.click(within(library).getByRole('button', { name: ADVANCED_TEXT.more }))
    expect(within(found).getAllByRole('listitem')).toHaveLength(11)
    expect(within(library).queryByRole('button', { name: ADVANCED_TEXT.more })).toBeNull()

    await user.clear(input)
    await user.type(input, '마포 구립')
    await user.click(within(library).getByRole('button', { name: '마포구립 전자도서관 더하기' }))
    expect(within(library).getByText('직접 추가 1/20')).toBeTruthy()
    const chosen = within(library).getByRole('list', { name: '직접 추가한 도서관' })
    expect(chosen.textContent).toContain('마포구립 전자도서관')
    expect(
      (within(library).getByRole('button', { name: '마포구립 전자도서관 직접 추가함' }) as HTMLButtonElement).disabled,
    ).toBe(true)
    expect(JSON.parse(localStorage.getItem(CHOSEN_LIBRARIES_KEY)!)).toEqual([1])

    await user.click(within(chosen).getByRole('button', { name: '마포구립 전자도서관 빼기' }))
    expect(within(library).getByText('직접 추가 0/20')).toBeTruthy()
    expect(JSON.parse(localStorage.getItem(CHOSEN_LIBRARIES_KEY)!)).toEqual([])
    expect(onSearch).not.toHaveBeenCalled()
  })

  it('a name without a match says so', async () => {
    withLibraryList()
    const { user } = setup()
    await user.click(moreButton())
    await user.type(screen.getByRole('searchbox', { name: ADVANCED_TEXT.nameSearch }), '없는이름')
    expect(await screen.findByText(ADVANCED_TEXT.noMatch)).toBeTruthy()
  })

  it('the 전국 box goes with that one search, then clears; it is never stored', async () => {
    const { user, onSearch } = setup()
    await user.type(titleBox(), '사랑')
    await user.click(moreButton())
    await user.click(check(ADVANCED_TEXT.all))
    await user.click(screen.getByRole('button', { name: '검색' }))

    expect(onSearch).toHaveBeenLastCalledWith(expect.objectContaining({ title: '사랑' }), { all: true })
    expect(check(ADVANCED_TEXT.all).checked).toBe(false)
    expect(JSON.stringify(loadPrefs())).not.toContain('"all"')

    await user.click(screen.getByRole('button', { name: '검색' }))
    expect(onSearch).toHaveBeenLastCalledWith(expect.objectContaining({ title: '사랑' }), { all: false })
  })

  it('a checked box in a closed panel does not count', async () => {
    const { user, onSearch } = setup()
    await user.type(titleBox(), '사랑')
    await user.click(moreButton())
    await user.click(check(ADVANCED_TEXT.all))
    await user.click(moreButton())
    await user.click(screen.getByRole('button', { name: '검색' }))
    expect(onSearch).toHaveBeenLastCalledWith(expect.objectContaining({ title: '사랑' }), { all: false })
  })
})

/** The same on the search screen: the address, the requests, and the [직접 추가] tag on a result row. */
describe('상세검색 on the search screen', () => {
  let address = ''
  function Address() {
    address = useLocation().search
    return null
  }

  function renderSearch(start = '/search?title=사랑') {
    render(
      <Providers>
        <MemoryRouter initialEntries={[start]}>
          <Address />
          <Routes>
            <Route path="/search" element={<SearchPage />} />
          </Routes>
        </MemoryRouter>
      </Providers>,
    )
    return userEvent.setup()
  }

  const lastStream = () => vi.mocked(holdingStream).mock.calls.at(-1)![0]

  it('전국 box + [검색] → scope=all in the address; the next new search is joinable (AC-19, D10)', async () => {
    const user = renderSearch()
    await vi.waitFor(() => expect(holdingStream).toHaveBeenCalled())
    expect(lastStream().scope).toBe('joinable')

    await user.click(moreButton())
    await user.click(check(ADVANCED_TEXT.all))
    await user.click(screen.getByRole('button', { name: '검색' }))
    await vi.waitFor(() => expect(lastStream().scope).toBe('all'))
    expect(new URLSearchParams(address).get('scope')).toBe('all')
    expect(check(ADVANCED_TEXT.all).checked).toBe(false)

    await user.click(screen.getByRole('button', { name: '검색' }))
    await vi.waitFor(() => expect(lastStream().scope).toBe('joinable'))
    expect(new URLSearchParams(address).get('scope')).toBeNull()
  })

  it('changing regions sends no search; [검색] then searches with them (AC-20)', async () => {
    const user = renderSearch()
    await vi.waitFor(() => expect(holdingStream).toHaveBeenCalledTimes(1))
    const books = vi.mocked(fetchBooks).mock.calls.length

    await user.click(moreButton())
    const residence = screen.getByRole('group', { name: ADVANCED_TEXT.residence })
    await user.click(await within(residence).findByRole('checkbox', { name: '강남구' }))
    await user.click(screen.getByRole('button', { name: ADVANCED_TEXT.addWork }))
    const work = screen.getByRole('group', { name: ADVANCED_TEXT.work })
    await user.click(await within(work).findByRole('checkbox', { name: '강서구' }))
    // give any effect the chance to run
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(holdingStream).toHaveBeenCalledTimes(1)
    expect(vi.mocked(fetchBooks).mock.calls.length).toBe(books)

    await user.click(screen.getByRole('button', { name: '검색' }))
    await vi.waitFor(() => expect(holdingStream).toHaveBeenCalledTimes(2))
    expect(lastStream().residenceRegionIds).toEqual([1168])
    expect(lastStream().workRegionIds).toEqual([1150])
  })

  it('[더하기] puts the library into 내 도서관; after [검색] its row carries [직접 추가] (AC-12)', async () => {
    withLibraryList()
    vi.mocked(holdingStream).mockImplementation(async (params, onEvent) =>
      answer(onEvent, [target(1, { name: '마포구립 전자도서관', chosen: params.libraryIds.includes(1) })]),
    )
    const user = renderSearch()
    await vi.waitFor(() => expect(holdingStream).toHaveBeenCalledTimes(1))
    expect(screen.queryByRole('button', { name: '직접 추가' })).toBeNull()

    await user.click(moreButton())
    await user.type(screen.getByRole('searchbox', { name: ADVANCED_TEXT.nameSearch }), '마포')
    await user.click(await screen.findByRole('button', { name: '마포구립 전자도서관 더하기' }))
    expect(screen.getByText('직접 추가 1/20')).toBeTruthy()
    // saved only: no search yet
    expect(holdingStream).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: '검색' }))
    await vi.waitFor(() => expect(holdingStream).toHaveBeenCalledTimes(2))
    expect(lastStream().libraryIds).toEqual([1])
    expect(await screen.findByRole('button', { name: '직접 추가' })).toBeTruthy()
  })
})
