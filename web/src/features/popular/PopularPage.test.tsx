import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BookDetail, PopularBooks } from '../../api/types'
import { PopularPage } from './PopularPage'
import { REQUEST_LINES } from './popularText'
import { CATEGORY_NAMES, testPopular } from './testPopular'

const REGIONS = {
  regions: { residence: [1116], work: [] },
  libraryIds: [12, 34],
  ready: true,
  source: 'browser',
  resolveStoredRegions: () => Promise.resolve(false),
}
vi.mock('../region/RegionContext', () => ({ useRegions: () => REGIONS }))
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

/** Answers the popular API with `popular`; the book API with one book; anything else (the holding stream) with 400. */
function stubFetch(popular: () => Response | Promise<Response> = () => json(testPopular())) {
  const calls: URL[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      const url = new URL(input, 'http://localhost')
      calls.push(url)
      if (url.pathname === '/api/v1/popular-books') return popular()
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
    <MemoryRouter initialEntries={['/popular']}>
      <Routes>
        <Route path="/popular" element={<PopularPage />} />
        <Route path="/books/:bookId" element={<p>책 화면</p>} />
      </Routes>
    </MemoryRouter>,
  )
  return userEvent.setup()
}

const section = (name: string) => screen.getByRole('region', { name })
const cards = (name: string) => within(section(name)).getAllByRole('button', { name: /^\d+위 / })
const ranks = (name: string) => cards(name).map((card) => Number(card.getAttribute('aria-label')!.split('위')[0]))

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
})

describe('popular books (S13)', () => {
  it('shows ranks 1-9 of each category with rank numbers, the title and the source line (AC-1)', async () => {
    stubFetch()
    renderPage()
    expect(await screen.findByRole('region', { name: '전체' })).toBeTruthy()
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('인기 도서 · 2026년 9월 판매 순위')
    expect(ranks('전체')).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9])
    expect(cards('전체')[0].getAttribute('aria-label')).toBe('1위 전체 1, 전자책 있음')
    expect(cards('전체')[0].textContent).toContain('1')
    expect(
      screen.getByText(
        '출처: 출판유통통합전산망 (교보문고·예스24·알라딘·영풍문고·지역서점 종이책 판매 합산) · 2026년 9월 · 10월 3일 받음',
      ),
    ).toBeTruthy()
  })

  it('turns 9 books at a time: no ◀ first, ▶ to 10-18, the last page 46-50 with no ▶ (AC-2)', async () => {
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    expect(within(section('전체')).queryByRole('button', { name: '전체 이전 9권' })).toBeNull()

    await user.click(within(section('전체')).getByRole('button', { name: '전체 다음 9권' }))
    expect(ranks('전체')).toEqual([10, 11, 12, 13, 14, 15, 16, 17, 18])
    expect(within(section('전체')).getByRole('button', { name: '전체 이전 9권' })).toBeTruthy()

    for (let i = 0; i < 4; i++) await user.click(within(section('전체')).getByRole('button', { name: '전체 다음 9권' }))
    expect(ranks('전체')).toEqual([46, 47, 48, 49, 50])
    expect(within(section('전체')).queryByRole('button', { name: '전체 다음 9권' })).toBeNull()
  })

  it('keeps each category on its own page and in this history entry', async () => {
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await user.click(within(section('전체')).getByRole('button', { name: '전체 다음 9권' }))
    expect(ranks('소설')[0]).toBe(1)
    await user.click(within(section('소설')).getByRole('button', { name: '소설 다음 9권' }))
    expect(ranks('전체')[0]).toBe(10)
    expect(window.history.state.chaekcheckPopular.pages).toEqual({ ALL: 1, '00000000000000000001': 1 })
  })

  it('comes back to the saved pages and scroll position (AC-20)', async () => {
    window.history.replaceState({ chaekcheckPopular: { pages: { ALL: 2 }, scrollY: 640 } }, '')
    const scrollTo = vi.fn()
    vi.stubGlobal('scrollTo', scrollTo)
    stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    expect(ranks('전체')[0]).toBe(19)
    expect(scrollTo).toHaveBeenCalledWith(0, 640)
  })

  it('keeps the categories in the order of the answer (AC-4)', async () => {
    stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(CATEGORY_NAMES)
  })

  it('fades books with no e-book in my libraries, with no words (AC-6)', async () => {
    stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    const [first, second] = cards('전체')
    expect(first.getAttribute('data-available')).toBe('true')
    expect(second.getAttribute('data-available')).toBe('false')
    expect(second.getAttribute('aria-label')).toBe('2위 전체 2, 전자책 없음')
    expect(second.textContent).toBe('2')
  })

  it('sends the saved regions, scope and added libraries (AC-7)', async () => {
    const calls = stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    const url = calls.find((c) => c.pathname === '/api/v1/popular-books')!
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
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('인기 도서')
  })

  it('says the ranking is being prepared when there is none yet', async () => {
    const empty: PopularBooks = { month: null, fetchedAt: null, categories: [] }
    stubFetch(() => json(empty))
    renderPage()
    expect(await screen.findByText('아직 인기 도서를 준비하고 있어요')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /분류/ })).toBeNull()
  })

  it('says it could not load when the request fails', async () => {
    stubFetch(() => json({ title: 'Internal Server Error', status: 500, code: 'internal_error' }, 500))
    renderPage()
    expect((await screen.findByRole('alert')).textContent).toBe('인기 도서를 불러오지 못했어요. 잠시 뒤에 다시 해 주세요.')
  })

  it('turns the page with a sideways swipe, not with a short one (AC-19)', async () => {
    stubFetch()
    renderPage()
    await screen.findByRole('region', { name: '전체' })
    const grid = within(section('전체')).getByRole('list')
    fireEvent.pointerDown(grid, { clientX: 300, clientY: 400, pointerType: 'touch' })
    fireEvent.pointerUp(grid, { clientX: 270, clientY: 400, pointerType: 'touch' })
    expect(ranks('전체')[0]).toBe(1)
    fireEvent.pointerDown(grid, { clientX: 300, clientY: 400, pointerType: 'touch' })
    fireEvent.pointerUp(grid, { clientX: 200, clientY: 410, pointerType: 'touch' })
    expect(ranks('전체')[0]).toBe(10)
    fireEvent.pointerDown(grid, { clientX: 200, clientY: 400, pointerType: 'touch' })
    fireEvent.pointerUp(grid, { clientX: 300, clientY: 400, pointerType: 'touch' })
    expect(ranks('전체')[0]).toBe(1)
  })

  it('picks a category from the list: closed, label changed, ranks 1-9 (AC-5)', async () => {
    const scrollIntoView = vi.fn()
    Element.prototype.scrollIntoView = scrollIntoView
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await user.click(within(section('역사')).getByRole('button', { name: '역사 다음 9권' }))
    await user.click(screen.getByRole('button', { name: '분류: 전체' }))
    const list = screen.getByRole('list', { name: '분류' })
    expect(within(list).getAllByRole('button')).toHaveLength(17)
    await user.click(within(list).getByRole('button', { name: '역사' }))
    expect(screen.queryByRole('list', { name: '분류' })).toBeNull()
    expect(screen.getByRole('button', { name: '분류: 역사' })).toBeTruthy()
    expect(ranks('역사')[0]).toBe(1)
    await waitFor(() => expect(scrollIntoView).toHaveBeenCalledWith({ block: 'start', behavior: 'instant' }))
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
    expect(within(dialog).queryByTestId('sheet-handle')).toBeNull()
    await user.click(within(dialog).getByRole('button', { name: '책 검색 >>' }))
    expect(await screen.findByText('책 화면')).toBeTruthy()
  })
})

/** AC-18: on phones the four windows are bottom sheets with a handle and ⓧ; back closes only the window. */
describe('popular windows on phones', () => {
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

  it('the help is a sheet closed by ⓧ', async () => {
    phoneWidth()
    stubFetch()
    const user = renderPage()
    await screen.findByRole('region', { name: '전체' })
    await user.click(screen.getByRole('button', { name: '인기 도서 안내' }))
    const sheet = screen.getByRole('dialog', { name: '인기 도서 안내' })
    expect(within(sheet).getByText('흐린 책은 검색하는 도서관에 전자책이 없어요')).toBeTruthy()
    expect(within(sheet).getByText('2026년 9월 판매 순위 · 10월 3일 받음')).toBeTruthy()
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
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('인기 도서 · 2026년 9월 판매 순위')
  })
})
