import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BookDetail, Picks } from '../../api/types'
import { sendUsageEvent } from '../../api/usageEvents'
import { PicksRoute } from './PicksPage'
import { REQUEST_LINES } from './picksText'
import { CATEGORIES, testBooks, testPicks } from './testPicks'

const REGIONS = {
  regions: { residence: [1116], work: [] },
  libraryIds: [12, 34],
  ready: true,
  source: 'browser',
  resolveStoredRegions: () => Promise.resolve(false),
}
vi.mock('../region/RegionContext', () => ({ useRegions: () => REGIONS }))
vi.mock('../../api/usageEvents', () => ({ sendUsageEvent: vi.fn() }))

const BOOK_DETAIL: BookDetail = {
  id: 5001,
  title: '우리 전자책 제목',
  authorDisplay: '지은이',
  publisher: '출판사',
  publishedOn: '2026-01-01',
  isbn13: '9780000000001',
  holdingLibraryCount: 2,
  libraries: [],
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

/** Answers the picks API with `picks`; the book API with one book; anything else (the holding stream) with 400. */
function stubFetch(picks: () => Response | Promise<Response> = () => json(testPicks())) {
  const calls: URL[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      const url = new URL(input, 'http://localhost')
      calls.push(url)
      if (url.pathname === '/api/v1/picks/librarian') return picks()
      if (url.pathname.startsWith('/api/v1/books/')) return json(BOOK_DETAIL)
      return json({ title: 'Bad Request', status: 400, code: 'validation_error' }, 400)
    }),
  )
  return calls
}

/** Phones: `(width <= 640px)` matches. */
function phoneWidth() {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('640px'),
    media: query,
    addEventListener() {},
    removeEventListener() {},
  }))
}

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/picks/librarian']}>
      <Routes>
        <Route path="/picks/:listKey" element={<PicksRoute />} />
        <Route path="/books/:bookId" element={<p>책 화면</p>} />
      </Routes>
    </MemoryRouter>,
  )
  return userEvent.setup()
}

const section = (name: string) => screen.getByRole('region', { name })
const cards = (name: string) => within(section(name)).getAllByRole('button', { name: /, 전자책 (있음|없음)$/ })
/** The card of the test list's book n ("전체 2"), wherever the e-book order put it. */
const card = (name: string, n: number) => within(section(name)).getByRole('button', { name: new RegExp(`^${name} ${n},`) })
/** The number at the end of each card's title ("전체 3" → 3): the place in the test list. */
const numbers = (name: string) =>
  cards(name).map((card) => Number(card.getAttribute('aria-label')!.split(',')[0].split(' ').pop()))
const chips = () => within(screen.getByRole('group', { name: '분류' })).getAllByRole('button')

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

beforeEach(() => {
  window.history.replaceState(null, '')
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.mocked(sendUsageEvent).mockClear()
})

describe('librarian picks (S13, redesign 2026-10-19)', () => {
  it('shows the tabs, the chips and every book of 전체 as cards (AC-1)', async () => {
    stubFetch()
    renderPage()
    expect(await screen.findByRole('region', { name: '전체' })).toBeTruthy()
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('사서 추천도서')
    const tabs = within(screen.getByRole('navigation', { name: '추천도서 목록' })).getAllByRole('link')
    expect(tabs.map((tab) => [tab.textContent, tab.getAttribute('href')])).toEqual([
      ['사서 추천', '/picks/librarian'],
      ['이동진 추천', '/picks/leedongjin'],
      ['서울대 100선', '/picks/snu100'],
    ])
    expect(tabs[0].getAttribute('aria-current')).toBe('page')
    expect(screen.queryByText(/출처와 추천 기준을 함께/)).toBeNull()
    expect(chips().map((chip) => chip.textContent)).toEqual(CATEGORIES.map((c) => c.name))
    expect(chips()[0].getAttribute('aria-pressed')).toBe('true')
    // books with an e-book (odd numbers in the test list) first (user 2026-10-19)
    expect(numbers('전체')).toEqual([1, 3, 5, 7, 9, 2, 4, 6, 8, 10])
    expect(card('전체', 1).getAttribute('aria-label')).toBe('전체 1, 전자책 있음')
    // no grey lines (user 2026-10-19: "회색깔 설명 글 다 빼줘")
    expect(screen.queryByText(/보유 정보를 찾았다는 뜻/)).toBeNull()
    // AC-15
    expect(document.body.textContent).not.toMatch(/인기 도서|순위|출판유통/)
  })

  it('marks each card linked or not, with no line under the title (AC-6)', async () => {
    stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    const [first, second] = [card('전체', 1), card('전체', 2)]
    expect(first.getAttribute('data-linked')).toBe('true')
    expect(within(first).getByText('전자책 있음')).toBeTruthy()
    expect(within(first).queryByText(/사서추천/)).toBeNull()
    expect(second.getAttribute('data-linked')).toBe('false')
    expect(within(second).getByText('전자책 없음')).toBeTruthy()
  })

  it('keeps the source and its licence in 추천 기준과 출처 보기 (R7-1)', async () => {
    stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    const summary = screen.getByText('추천 기준과 출처 보기')
    expect(summary.tagName).toBe('SUMMARY')
    const details = summary.closest('details')!
    expect(within(details).getByText('국립중앙도서관 사서가 고른 신간 추천 도서 중 최근 12번(2년) 추천분이에요')).toBeTruthy()
    expect(within(details).getByText('출처: 국립중앙도서관 사서추천도서 (공공누리 제1유형) · 10월 12일 받음')).toBeTruthy()
  })

  it('filters the grid with a chip and keeps the chip in this history entry', async () => {
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await user.click(screen.getByRole('button', { name: '사회' }))
    expect(screen.queryByRole('region', { name: '전체' })).toBeNull()
    expect(numbers('사회')).toEqual([1, 3, 5, 7, 9, 2, 4, 6, 8, 10])
    expect(screen.getByRole('button', { name: '사회' }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByRole('button', { name: '전체' }).getAttribute('aria-pressed')).toBe('false')
    expect(window.history.state['chaekcheckPicks:librarian'].category).toBe('5')
  })

  it('shows 20 books, then 20 more with 더 보기', async () => {
    const many = testPicks({
      categories: [{ code: 'ALL', name: '전체', books: testBooks('전체', '11', 45) }],
    })
    stubFetch(() => json(many))
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    expect(cards('전체')).toHaveLength(20)
    await user.click(screen.getByRole('button', { name: /^더 보기/ }))
    expect(cards('전체')).toHaveLength(40)
    expect(screen.getByRole('button', { name: /^더 보기/ }).textContent).toBe('더 보기 (5권)')
    await user.click(screen.getByRole('button', { name: /^더 보기/ }))
    expect(cards('전체')).toHaveLength(45)
    expect(screen.queryByRole('button', { name: /^더 보기/ })).toBeNull()
  })

  it('comes back to the saved chip and scroll position (AC-20)', async () => {
    window.history.replaceState({ 'chaekcheckPicks:librarian': { category: '6', shown: 20, scrollY: 640 } }, '')
    const scrollTo = vi.fn()
    vi.stubGlobal('scrollTo', scrollTo)
    stubFetch()
    renderPage()
    expect(await screen.findByRole('region', { name: '인문' })).toBeTruthy()
    expect(scrollTo).toHaveBeenCalledWith(0, 640)
  })

  it('keeps the fields in the order of the answer, a new field last (AC-4, AC-4b)', async () => {
    stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    expect(chips().at(-1)!.textContent).toBe('새분야')
  })

  it('sends the saved regions, scope and added libraries (AC-7)', async () => {
    const calls = stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    const url = calls.find((c) => c.pathname === '/api/v1/picks/librarian')!
    expect(url.searchParams.get('scope')).toBe('joinable')
    expect(url.searchParams.get('residenceRegionIds')).toBe('1116')
    expect(url.searchParams.get('libraryIds')).toBe('12,34')
    expect(url.searchParams.has('visit')).toBe(false)
  })

  it('is joinable even when this browser kept the old 전체 switch (cycle 22 R4-1)', async () => {
    localStorage.setItem('chaekcheck.searchSettings.v1', JSON.stringify({ guestScope: 'all', myScope: 'all' }))
    const calls = stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    expect(calls.find((c) => c.pathname === '/api/v1/picks/librarian')!.searchParams.get('scope')).toBe('joinable')
    localStorage.clear()
  })

  it('shows the title when there is no cover or it does not load (AC-14)', async () => {
    stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    const [first, third] = [card('전체', 1), card('전체', 3)]
    expect(within(third).getByTestId('title-cover').textContent).toBe('전체 3')
    const image = first.querySelector('img')!
    expect(image.getAttribute('loading')).toBe('lazy')
    fireEvent.error(image)
    expect(first.querySelector('img')).toBeNull()
    expect(within(first).getByTestId('title-cover').textContent).toBe('전체 1')
  })

  it('shows gray cards while loading', () => {
    stubFetch(() => new Promise<Response>(() => {}))
    renderPage()
    expect(screen.getAllByTestId('skeleton-card')).toHaveLength(8)
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('사서 추천도서')
    expect(screen.getByRole('status').textContent).toBe('사서추천도서를 불러오는 중')
  })

  it('says the picks are being prepared when there are none yet', async () => {
    const empty: Picks = { list: 'librarian', fetchedAt: null, categories: [] }
    stubFetch(() => json(empty))
    renderPage()
    expect(await screen.findByText('아직 사서추천도서를 준비하고 있어요')).toBeTruthy()
    expect(screen.queryByRole('group', { name: '분류' })).toBeNull()
  })

  it('says it could not load when the request fails', async () => {
    stubFetch(() => json({ title: 'Internal Server Error', status: 500, code: 'internal_error' }, 500))
    renderPage()
    expect((await screen.findByRole('alert')).textContent).toBe('사서추천도서를 불러오지 못했어요. 잠시 뒤에 다시 해 주세요.')
  })

  it('a book not linked opens the request notice with its title (AC-10)', async () => {
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await user.click(card('전체', 2))
    const dialog = screen.getByRole('dialog', { name: '전자책 신청 안내' })
    expect(within(dialog).getByText('전체 2')).toBeTruthy()
    expect(within(dialog).getByText(REQUEST_LINES[0], { exact: false })).toBeTruthy()
    await user.click(within(dialog).getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('a linked book opens S14 on PC; [책 검색 >>] goes to the book screen (AC-9)', async () => {
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await user.click(card('전체', 1))
    const dialog = screen.getByRole('dialog', { name: '전체 1 대출 정보' })
    // cycle 21 (ADR-0129): the shown name the list gives
    expect(await within(dialog).findByText('지은이 · 출판사')).toBeTruthy()
    expect(within(dialog).getByRole('heading', { name: '전체 1' })).toBeTruthy()
    expect(within(dialog).queryByText(/사서추천/)).toBeNull()
    expect(within(dialog).queryByTestId('sheet-handle')).toBeNull()
    await user.click(within(dialog).getByRole('button', { name: '책 검색' }))
    expect(await screen.findByText('책 화면')).toBeTruthy()
  })
})

/** Cycle 15: usage reports of the picks screen (AC-23). */
describe('usage reports', () => {
  const reported = (type: string) =>
    vi
      .mocked(sendUsageEvent)
      .mock.calls.map(([event]) => event)
      .filter((event) => event.type === type)

  it('the shown category once each time it changes, the first one included', async () => {
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await waitFor(() => expect(reported('picks_list_view')).toEqual([{ type: 'picks_list_view', list: 'librarian', category: 'ALL' }]))

    await user.click(screen.getByRole('button', { name: '사회' }))
    await user.click(screen.getByRole('button', { name: '사회' }))

    expect(reported('picks_list_view')).toEqual([
      { type: 'picks_list_view', list: 'librarian', category: 'ALL' },
      { type: 'picks_list_view', list: 'librarian', category: '5' },
    ])
  })

  it('an opened book window with its field and book; a book not linked opens none', async () => {
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })

    await user.click(card('전체', 2))
    expect(reported('picks_book_view')).toEqual([])
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '닫기' }))

    await user.click(card('전체', 1))
    expect(reported('picks_book_view')).toEqual([{ type: 'picks_book_view', list: 'librarian', category: '11', bookId: 5001 }])
  })
})

/** AC-18: on phones the windows are bottom sheets with a handle and ⓧ; back closes only the window. */
describe('picks windows on phones', () => {
  it('the request notice is a sheet closed by pulling the handle down', async () => {
    phoneWidth()
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await user.click(card('전체', 2))
    const handle = within(screen.getByRole('dialog', { name: '전자책 신청 안내' })).getByTestId('sheet-handle')
    fireEvent.pointerDown(handle, { clientY: 500, pointerId: 1 })
    fireEvent.pointerMove(handle, { clientY: 600, pointerId: 1 })
    fireEvent.pointerUp(handle, { clientY: 600, pointerId: 1 })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('S14 is a sheet; back closes it and the screen stays', async () => {
    phoneWidth()
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await user.click(card('전체', 1))
    const sheet = screen.getByRole('dialog', { name: '전체 1 대출 정보' })
    expect(within(sheet).getByTestId('sheet-handle')).toBeTruthy()
    await waitFor(() => expect(window.history.state).toHaveProperty('chaekcheckSheet'))
    window.history.back()
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('사서 추천도서')
  })
})
