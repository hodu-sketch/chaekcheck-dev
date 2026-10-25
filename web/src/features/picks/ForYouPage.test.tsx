import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, render, renderHook, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Navigate, Route, Routes } from 'react-router'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BookDetail, ForYouCard } from '../../api/types'
import { sendUsageEvent } from '../../api/usageEvents'
import { BaseBooksDialog } from './BaseBooksDialog'
import { ForYouPage } from './ForYouPage'
import { DWELL_MS, useBookVisit } from './useBookVisit'
import { baseBooks, points, readViewed, recordLibraryClicked, recordOpened } from './viewedBooks'

const REGIONS = {
  regions: { residence: [1116], work: [] },
  libraryIds: [],
  ready: true,
  source: 'browser',
  resolveStoredRegions: () => Promise.resolve(false),
}
vi.mock('../region/RegionContext', () => ({ useRegions: () => REGIONS }))
vi.mock('../../api/usageEvents', () => ({ sendUsageEvent: vi.fn() }))

const DAY = 24 * 60 * 60 * 1000

function card(bookId: number, extra: Partial<ForYouCard> = {}): ForYouCard {
  return {
    bookId,
    title: `책 ${bookId}`,
    author: '지은이',
    publisher: '출판사',
    coverUrl: null,
    blurb: null,
    tags: [],
    planLibraryCount: 2,
    lists: [],
    authorKey: `a${bookId}`,
    ...extra,
  }
}

const BOOK_DETAIL: BookDetail = {
  id: 1,
  title: '책 1',
  authorDisplay: '지은이',
  publisher: '출판사',
  publishedOn: '2026-01-01',
  isbn13: null,
  holdingLibraryCount: 0,
  libraries: [],
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

type Answers = {
  books?: () => Response
  similar?: () => Response
}

function stubFetch(answers: Answers = {}) {
  const calls: URL[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      const url = new URL(input, 'http://localhost')
      calls.push(url)
      if (url.pathname === '/api/v1/for-you/books') {
        return (answers.books ?? (() => json({ builtOn: '2026-10-26', books: [card(1), card(2), card(3)] })))()
      }
      if (url.pathname === '/api/v1/for-you/similar-books') {
        return (answers.similar ?? (() => json({ baseBooks: [], books: [card(10), card(11)], reshowBooks: [] })))()
      }
      if (url.pathname.startsWith('/api/v1/books/')) return json(BOOK_DETAIL)
      return json({ title: 'Bad Request', status: 400, code: 'validation_error' }, 400)
    }),
  )
  return calls
}

function renderAt(path = '/picks/for-you') {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/picks" element={<Navigate to="/picks/for-you" replace />} />
          <Route path="/picks/for-you" element={<ForYouPage />} />
          <Route path="/mypage" element={<p>마이페이지 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return userEvent.setup()
}

const similarCalls = (calls: URL[]) => calls.filter((url) => url.pathname === '/api/v1/for-you/similar-books')
const cardButtons = () => within(screen.getByRole('region', { name: '책첵 추천 목록' })).getAllByRole('button')
const cardTitles = () => cardButtons().map((button) => button.querySelector('span[class*="title"]')?.textContent)

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

beforeEach(() => localStorage.clear())

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  vi.mocked(sendUsageEvent).mockClear()
})

describe('S18 for-you (cycle 23)', () => {
  it('/picks opens the tab, the first of the row (AC-1, AC-16)', async () => {
    stubFetch()
    renderAt('/picks')
    const tabs = within(await screen.findByRole('navigation', { name: '추천도서 목록' })).getAllByRole('link')
    expect(tabs[0].textContent?.replace(/\u00a0/g, ' ')).toBe('책첵 추천')
    expect(tabs[0].getAttribute('aria-current')).toBe('page')
  })

  it('without a record: the AI line once, no base books line, the base list only, no personal request (AC-2, AC-9)', async () => {
    const calls = stubFetch()
    renderAt()
    await screen.findByRole('region', { name: '책첵 추천 목록' })
    expect(screen.getAllByText('책 소개 글과 꼬리표는 AI가 골랐어요')).toHaveLength(1)
    expect(screen.queryByText(/최근 본 책 .*비슷한 책/)).toBeNull()
    expect(cardTitles()).toEqual(['책 1', '책 2', '책 3'])
    expect(similarCalls(calls)).toHaveLength(0)
    expect(sendUsageEvent).toHaveBeenCalledWith({ type: 'picks_list_view', list: 'for_you', category: 'all' })
  })

  it('cards: the footer only for listed books with "외 N", tags only when given, no AI mark (AC-3, AC-10)', async () => {
    stubFetch({
      books: () =>
        json({
          builtOn: '2026-10-26',
          books: [
            card(1, {
              lists: ['kaist', 'snu100', 'nobel'],
              blurb: '잔잔하게 읽히는 이야기예요.',
              tags: [
                { code: 'm1', axis: 'mood', name: '잔잔한' },
                { code: 's1', axis: 'subject', name: '소설' },
              ],
            }),
            card(2),
          ],
        }),
    })
    const user = renderAt()
    await screen.findByRole('region', { name: '책첵 추천 목록' })
    const [first, second] = cardButtons()
    // no list footer on the cards (user 2026-10-25)
    expect(first.textContent).not.toContain('추천목록에 있어요')
    // no personal picks: the whole list is the base list, no heading between its cards
    expect(screen.queryByRole('heading', { name: '요즘 많이 빌리는 책과 추천목록 책' })).toBeNull()
    expect(first.textContent).toContain('잔잔하게 읽히는 이야기예요.')
    expect(within(first).getByText('잔잔한')).toBeTruthy()
    expect(first.textContent).toContain('내 도서관 2곳')
    expect(second.textContent).not.toContain('추천목록에 있어요')
    expect(second.textContent).not.toContain('잔잔한')
    expect(screen.queryByText(/AI 추천/)).toBeNull()
    // a tag does nothing: it is not a button of its own
    await user.click(within(first).getByText('잔잔한'))
    expect(within(first).queryAllByRole('button')).toHaveLength(0)
  })

  it('with base books: personal picks first, the base list after them, sent ids only; the line is on 마이페이지', async () => {
    const now = Date.now()
    recordOpened(501, '고래', now)
    recordLibraryClicked(501, now)
    recordOpened(502, '파과', now - 1000)
    recordOpened(505, '흰', now - 2000)
    recordOpened(506, '토지', now - 3000)
    recordOpened(507, '채식주의자', now - 4000)
    recordOpened(503, '아몬드', now - 5 * DAY)
    recordOpened(504, '옛 책', now - 6 * DAY)
    const calls = stubFetch({
      similar: () => json({ baseBooks: [], books: [card(10), card(2)], reshowBooks: [] }),
    })
    renderAt()
    await screen.findByRole('region', { name: '책첵 추천 목록' })
    // the base books' line and [기준 책 바꾸기] moved to 마이페이지 (user 2026-10-25)
    expect(screen.queryByText(/최근 본 책 .*비슷한 책/)).toBeNull()
    expect(screen.queryByRole('button', { name: '기준 책 바꾸기' })).toBeNull()
    // 2 is a personal pick and in the base list: once, with the personal picks
    expect(cardTitles()).toEqual(['책 10', '책 2', '책 1', '책 3'])
    // after the personal picks the base list has its own heading (user 2026-10-25)
    expect(screen.getByRole('heading', { name: '요즘 많이 빌리는 책과 추천목록 책' })).toBeTruthy()
    const [similar] = similarCalls(calls)
    // six base books since 2026-10-25 ("기준 책이 너무 작아")
    expect(similar.searchParams.get('baseBookIds')).toBe('501,502,505,506,507,503')
    expect(similar.searchParams.get('reshowBookIds')).toBe('504')
  })

  it('a failed personal request keeps the base list and offers a retry (AC-14)', async () => {
    recordOpened(501, '고래')
    let fail = true
    const calls = stubFetch({
      similar: () => (fail ? json({ title: 'Internal', status: 500, code: 'internal_error' }, 500) : json({ baseBooks: [], books: [card(10)], reshowBooks: [] })),
    })
    const user = renderAt()
    expect(await screen.findByText('비슷한 책을 불러오지 못했어요')).toBeTruthy()
    expect(cardTitles()).toEqual(['책 1', '책 2', '책 3'])
    fail = false
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(cardTitles()[0]).toBe('책 10'))
    expect(similarCalls(calls)).toHaveLength(2)
  })

  it('a failed base list says so and asks again', async () => {
    let fail = true
    stubFetch({
      books: () => (fail ? json({ title: 'Internal', status: 500, code: 'internal_error' }, 500) : json({ builtOn: null, books: [card(7)] })),
    })
    const user = renderAt()
    expect(await screen.findByText(/추천 책을 불러오지 못했어요/)).toBeTruthy()
    fail = false
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(cardTitles()).toEqual(['책 7']))
  })

  it('nothing in my libraries: the empty line and the way to 마이페이지', async () => {
    stubFetch({ books: () => json({ builtOn: '2026-10-26', books: [] }) })
    renderAt()
    expect(await screen.findByText('내 도서관에 있는 추천 책이 없어요')).toBeTruthy()
    expect(screen.getByRole('link', { name: '마이페이지에서 지역과 도서관 바꾸기' }).getAttribute('href')).toBe('/mypage')
  })

  it('opening a card reports the book with where it came from (AC-12)', async () => {
    recordOpened(501, '고래')
    stubFetch({ similar: () => json({ baseBooks: [], books: [card(10)], reshowBooks: [] }) })
    const user = renderAt()
    await screen.findByRole('region', { name: '책첵 추천 목록' })
    await user.click(cardButtons()[0])
    expect(sendUsageEvent).toHaveBeenCalledWith({ type: 'picks_book_view', list: 'for_you', category: 'similar', bookId: 10 })
    expect(vi.mocked(sendUsageEvent).mock.calls.every(([event]) => ['picks_list_view', 'picks_book_view'].includes(event.type))).toBe(true)
  })

  it('S19: [빼기] brings up the next book, clearing the record leaves no base book (AC-15)', async () => {
    const now = Date.now()
    recordOpened(501, '고래', now)
    recordOpened(502, '파과', now - 1000)
    recordOpened(503, '아몬드', now - 2000)
    recordOpened(504, '소년이 온다', now - 3000)
    const user = userEvent.setup()
    render(<BaseBooksDialog phone={false} onClose={() => {}} />)
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '고래 빼기' }))
    expect(within(dialog).queryByText('고래')).toBeNull()
    expect(baseBooks(readViewed(), Date.now()).map((book) => book.bookId)).toEqual([502, 503, 504])
    await user.click(within(dialog).getByRole('button', { name: '최근 본 책 기록 모두 지우기' }))
    expect(readViewed()).toEqual([])
  })
})

describe('useBookVisit (R5-3, R5-4)', () => {
  it('counts the open at once and 10 seconds only while visible', () => {
    vi.useFakeTimers()
    let hidden = false
    vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => (hidden ? 'hidden' : 'visible'))
    renderHook(() => useBookVisit(77, '머문 책'))
    expect(points(readViewed()[0])).toBe(1)

    act(() => vi.advanceTimersByTime(6000))
    hidden = true
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
      vi.advanceTimersByTime(60_000)
    })
    expect(points(readViewed()[0])).toBe(1)

    hidden = false
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
      vi.advanceTimersByTime(DWELL_MS - 6000 - 1)
    })
    expect(points(readViewed()[0])).toBe(1)
    act(() => vi.advanceTimersByTime(1))
    expect(points(readViewed()[0])).toBe(3)
  })
})
