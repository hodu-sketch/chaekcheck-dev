import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BookDetail, Picks } from '../../api/types'
import { sendUsageEvent } from '../../api/usageEvents'
import { PicksPage } from './PicksPage'
import { REQUEST_LINES } from './picksText'
import { CATEGORIES, testPicks } from './testPicks'

const REGIONS = {
  regions: { residence: [1116], work: [] },
  libraryIds: [12, 34],
  ready: true,
  source: 'browser',
  resolveStoredRegions: () => Promise.resolve(false),
}
vi.mock('../region/RegionContext', () => ({ useRegions: () => REGIONS }))
vi.mock('../../api/usageEvents', () => ({ sendUsageEvent: vi.fn() }))
vi.mock('../search/useLibraryScope', () => ({ useLibraryScope: () => ({ scope: 'joinable' }) }))

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
      if (url.pathname === '/api/v1/picks') return picks()
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
    <MemoryRouter initialEntries={['/picks']}>
      <Routes>
        <Route path="/picks" element={<PicksPage />} />
        <Route path="/books/:bookId" element={<p>책 화면</p>} />
      </Routes>
    </MemoryRouter>,
  )
  return userEvent.setup()
}

const section = (name: string) => screen.getByRole('region', { name })
const cards = (name: string) => within(section(name)).getAllByRole('button', { name: /, 전자책 (있음|없음)$/ })
/** The number at the end of each card's title ("전체 3" → 3): the place in the test list. */
const numbers = (name: string) =>
  cards(name).map((card) => Number(card.getAttribute('aria-label')!.split(',')[0].split(' ').pop()))

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

describe('librarian picks (S13)', () => {
  it('shows books 1-9 of each field with no rank chip, the card head and the source line (AC-1)', async () => {
    stubFetch()
    renderPage()
    expect(await screen.findByRole('region', { name: '전체' })).toBeTruthy()
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('사서추천도서')
    expect(numbers('전체')).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9])
    expect(cards('전체')[0].getAttribute('aria-label')).toBe('전체 1, 전자책 있음')
    // no rank chip: nothing but the cover (or its title) in a card
    expect(cards('전체')[0].querySelector('[aria-hidden="true"][data-available]')).toBeNull()
    expect(within(section('전체')).getByText('1 - 9').parentElement!.textContent).toBe('1 - 9 / 10')
    expect(within(section('전체')).getByRole('list').getAttribute('aria-label')).toBe('전체 1~9번째 / 10권')
    expect(screen.getByText('출처: 국립중앙도서관 사서추천도서 (공공누리 제1유형) · 10월 12일 받음')).toBeTruthy()
    // AC-15
    expect(document.body.textContent).not.toMatch(/인기 도서|순위|출판유통/)
  })

  it('turns 9 books at a time: no ◀ first, ▶ to the last page (book 10) with no ▶ (AC-2)', async () => {
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    expect(within(section('전체')).queryByRole('button', { name: '전체 이전 9권' })).toBeNull()

    await user.click(within(section('전체')).getByRole('button', { name: '전체 다음 9권' }))
    expect(numbers('전체')).toEqual([10])
    expect(within(section('전체')).getByRole('button', { name: '전체 이전 9권' })).toBeTruthy()
    expect(within(section('전체')).queryByRole('button', { name: '전체 다음 9권' })).toBeNull()
    expect(within(section('전체')).getByText('10 - 10')).toBeTruthy()
    // the 3 x 3 space stays: eight blank cells after the one book, so the panel keeps its size
    const cells = within(section('전체')).getByRole('list').querySelectorAll('li')
    expect(cells).toHaveLength(9)
    expect(Array.from(cells).filter((cell) => cell.hasAttribute('data-empty'))).toHaveLength(8)
  }, 15000)

  it('keeps each category on its own page and in this history entry', async () => {
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await user.click(within(section('전체')).getByRole('button', { name: '전체 다음 9권' }))
    expect(numbers('문학')[0]).toBe(1)
    await user.click(within(section('문학')).getByRole('button', { name: '문학 다음 9권' }))
    expect(numbers('전체')[0]).toBe(10)
    expect(window.history.state.chaekcheckPicks.pages).toEqual({ ALL: 1, '11': 1 })
  })

  it('comes back to the saved pages and scroll position (AC-20)', async () => {
    window.history.replaceState({ chaekcheckPicks: { pages: { ALL: 1 }, scrollY: 640 } }, '')
    const scrollTo = vi.fn()
    vi.stubGlobal('scrollTo', scrollTo)
    stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    expect(numbers('전체')[0]).toBe(10)
    expect(scrollTo).toHaveBeenCalledWith(0, 640)
  })

  it('keeps the fields in the order of the answer, a new field last (AC-4, AC-4b)', async () => {
    stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(
      CATEGORIES.map((c) => c.name),
    )
    expect(numbers('새분야')).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9])
  })

  it('fades books with no e-book in my libraries, with no words (AC-6)', async () => {
    stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    const [first, second] = cards('전체')
    expect(first.getAttribute('data-available')).toBe('true')
    expect(second.getAttribute('data-available')).toBe('false')
    expect(second.getAttribute('aria-label')).toBe('전체 2, 전자책 없음')
    expect(second.textContent).toBe('')
  })

  it('sends the saved regions, scope and added libraries (AC-7)', async () => {
    const calls = stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    const url = calls.find((c) => c.pathname === '/api/v1/picks')!
    expect(url.searchParams.get('scope')).toBe('joinable')
    expect(url.searchParams.get('residenceRegionIds')).toBe('1116')
    expect(url.searchParams.get('libraryIds')).toBe('12,34')
    expect(url.searchParams.has('visit')).toBe(false)
  })

  it('shows the title when there is no cover or it does not load (AC-14)', async () => {
    stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    const [first, , third] = cards('전체')
    expect(third.textContent).toContain('전체 3')
    const image = first.querySelector('img')!
    expect(image.getAttribute('loading')).toBe('lazy')
    fireEvent.error(image)
    expect(first.querySelector('img')).toBeNull()
    expect(first.textContent).toContain('전체 1')
  })

  it('shows nine gray cards while loading', () => {
    stubFetch(() => new Promise<Response>(() => {}))
    renderPage()
    expect(screen.getAllByTestId('skeleton-card')).toHaveLength(9)
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('사서추천도서')
    expect(screen.getByRole('status').textContent).toBe('사서추천도서를 불러오는 중')
  })

  it('says the picks are being prepared when there are none yet', async () => {
    const empty: Picks = { fetchedAt: null, categories: [] }
    stubFetch(() => json(empty))
    renderPage()
    expect(await screen.findByText('아직 사서추천도서를 준비하고 있어요')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /분류/ })).toBeNull()
  })

  it('says it could not load when the request fails', async () => {
    stubFetch(() => json({ title: 'Internal Server Error', status: 500, code: 'internal_error' }, 500))
    renderPage()
    expect((await screen.findByRole('alert')).textContent).toBe('사서추천도서를 불러오지 못했어요. 잠시 뒤에 다시 해 주세요.')
  })

  it('turns the page with a sideways swipe, not with a short one (AC-19)', async () => {
    stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    const grid = within(section('전체')).getByRole('list')
    fireEvent.pointerDown(grid, { clientX: 300, clientY: 400, pointerType: 'touch' })
    fireEvent.pointerUp(grid, { clientX: 270, clientY: 400, pointerType: 'touch' })
    expect(numbers('전체')[0]).toBe(1)
    fireEvent.pointerDown(grid, { clientX: 300, clientY: 400, pointerType: 'touch' })
    fireEvent.pointerUp(grid, { clientX: 200, clientY: 410, pointerType: 'touch' })
    expect(numbers('전체')[0]).toBe(10)
    fireEvent.pointerDown(grid, { clientX: 200, clientY: 400, pointerType: 'touch' })
    fireEvent.pointerUp(grid, { clientX: 300, clientY: 400, pointerType: 'touch' })
    expect(numbers('전체')[0]).toBe(1)
  })

  it('picks a field from the list: closed, label changed, books 1-9 (AC-5)', async () => {
    const scrollIntoView = vi.fn()
    Element.prototype.scrollIntoView = scrollIntoView
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await user.click(within(section('사회')).getByRole('button', { name: '사회 다음 9권' }))
    await user.click(screen.getByRole('button', { name: '분류: 전체' }))
    const list = screen.getByRole('list', { name: '분류' })
    expect(within(list).getAllByRole('button')).toHaveLength(7)
    await user.click(within(list).getByRole('button', { name: '사회' }))
    expect(screen.queryByRole('list', { name: '분류' })).toBeNull()
    expect(screen.getByRole('button', { name: '분류: 사회' })).toBeTruthy()
    expect(numbers('사회')[0]).toBe(1)
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalledWith({ block: 'start', behavior: 'instant' }))
  })

  it('at the very bottom the category box shows the last category (AC-3)', async () => {
    stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    Object.defineProperty(document.documentElement, 'scrollHeight', { configurable: true, value: 20000 })
    vi.stubGlobal('scrollY', 20000 - window.innerHeight)
    fireEvent.scroll(window)
    expect(await screen.findByRole('button', { name: '분류: 새분야' })).toBeTruthy()
    Reflect.deleteProperty(document.documentElement, 'scrollHeight')
  })

  it('a faded book opens the request notice with its title (AC-10)', async () => {
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await user.click(cards('전체')[1])
    const dialog = screen.getByRole('dialog', { name: '전자책 신청 안내' })
    expect(within(dialog).getByText('전체 2')).toBeTruthy()
    expect(within(dialog).getByText(REQUEST_LINES[0], { exact: false })).toBeTruthy()
    await user.click(within(dialog).getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('a book with an e-book opens S14 on PC; [책 검색 >>] goes to the book screen (AC-9)', async () => {
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await user.click(cards('전체')[0])
    const dialog = screen.getByRole('dialog', { name: '전체 1 대출 정보' })
    expect(await within(dialog).findByText('우리 전자책 제목')).toBeTruthy()
    // the book's field is 문학 (code 11), its month 2026-10
    expect(within(dialog).getByText('2026년 10월 사서추천 · 문학')).toBeTruthy()
    expect(within(dialog).queryByTestId('sheet-handle')).toBeNull()
    await user.click(within(dialog).getByRole('button', { name: '책 검색' }))
    expect(await screen.findByText('책 화면')).toBeTruthy()
  })
})

/** Cycle 15: usage reports of the picks screen (AC-23); the screen itself does not change. */
describe('usage reports', () => {
  const reported = (type: string) =>
    vi
      .mocked(sendUsageEvent)
      .mock.calls.map(([event]) => event)
      .filter((event) => event.type === type)

  it('the field in the middle of the screen once each time it changes, the first one included', async () => {
    Element.prototype.scrollIntoView = vi.fn()
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await waitFor(() => expect(reported('picks_list_view')).toEqual([{ type: 'picks_list_view', category: 'ALL' }]))

    await user.click(within(section('전체')).getByRole('button', { name: '전체 다음 9권' }))
    await user.click(screen.getByRole('button', { name: '분류: 전체' }))
    await user.click(within(screen.getByRole('list', { name: '분류' })).getByRole('button', { name: '사회' }))

    expect(reported('picks_list_view')).toEqual([
      { type: 'picks_list_view', category: 'ALL' },
      { type: 'picks_list_view', category: '5' },
    ])
  })

  it('an opened book window with its field and book; a faded book opens none', async () => {
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })

    await user.click(cards('전체')[1])
    expect(reported('picks_book_view')).toEqual([])
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '닫기' }))

    await user.click(cards('전체')[0])
    expect(reported('picks_book_view')).toEqual([{ type: 'picks_book_view', category: '11', bookId: 5001 }])
  })
})

/** Touch mode follows `(pointer: coarse)` alone: Samsung Internet on a phone reports `hover: hover` (2026-10-12). */
describe('touch mode', () => {
  function media(matching: string[]) {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: matching.includes(query),
      media: query,
      addEventListener() {},
      removeEventListener() {},
    }))
  }

  it('turns on when only (pointer: coarse) matches, even with hover', async () => {
    media(['(pointer: coarse)', '(hover: hover)'])
    stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    expect(document.querySelector('[data-touch]')).not.toBeNull()
  })

  it('stays off with a mouse', async () => {
    media(['(hover: hover)', '(pointer: fine)'])
    stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    expect(document.querySelector('[data-touch]')).toBeNull()
  })
})

/** AC-18: on phones the four windows are bottom sheets with a handle and ⓧ; back closes only the window. */
describe('picks windows on phones', () => {
  it('the category list is a sheet; back closes only it', async () => {
    phoneWidth()
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await user.click(screen.getByRole('button', { name: '분류: 전체' }))
    const sheet = screen.getByRole('dialog', { name: '분류 고르기' })
    expect(within(sheet).getByTestId('sheet-handle')).toBeTruthy()
    expect(within(sheet).getByRole('button', { name: '닫기' })).toBeTruthy()
    await waitFor(() => expect(window.history.state).toHaveProperty('chaekcheckSheet'))
    window.history.back()
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getByRole('region', { name: '전체' })).toBeTruthy()
  })

  it('the help is in the category sheet ("추천 기준 보기")', async () => {
    phoneWidth()
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await user.click(screen.getByRole('button', { name: '분류: 전체' }))
    const sheet = screen.getByRole('dialog', { name: '분류 고르기' })
    await user.click(within(sheet).getByRole('button', { name: '추천 기준 보기' }))
    expect(within(sheet).getByText('국립중앙도서관 사서가 고른 신간 추천 도서 중 최근 12번(2년) 추천분이에요')).toBeTruthy()
    expect(within(sheet).getByText('흐린 책은 검색하는 도서관에 전자책이 없어요')).toBeTruthy()
    expect(within(sheet).getByText('출처: 국립중앙도서관 사서추천도서 (공공누리 제1유형) · 10월 12일 받음')).toBeTruthy()
    await user.click(within(sheet).getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('the request notice is a sheet closed by pulling the handle down', async () => {
    phoneWidth()
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await user.click(cards('전체')[1])
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
    await user.click(cards('전체')[0])
    const sheet = screen.getByRole('dialog', { name: '전체 1 대출 정보' })
    expect(within(sheet).getByTestId('sheet-handle')).toBeTruthy()
    await waitFor(() => expect(window.history.state).toHaveProperty('chaekcheckSheet'))
    window.history.back()
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('사서추천도서')
  })
})
