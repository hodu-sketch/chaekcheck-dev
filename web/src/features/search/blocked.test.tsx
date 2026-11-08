import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, fetchBooks, type BookQuery } from '../../api/client'
import { holdingBatchStream, holdingStream } from '../../api/searchStream'
import type { BookPage, HoldingStreamEvent, TargetLibrary } from '../../api/types'
import { BLOCKED_TEXT, blockedBy } from './blocked'
import { BookList } from './BookList'
import { BUSY_CHECKING_TEXT, BUSY_KEEP_LIST_TEXT, BUSY_RETRY_LABEL } from './BusyRetryNotice'
import { EMPTY_HOLDINGS, holdingsReducer } from './holdingPlan'
import { InstantResults } from './InstantResults'

vi.mock('../../api/searchStream', () => ({ holdingStream: vi.fn(), holdingBatchStream: vi.fn() }))
vi.mock('../../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/client')>()),
  fetchBooks: vi.fn(),
}))

afterEach(cleanup)

const QUERY: BookQuery = {
  title: '사랑',
  author: null,
  publisher: null,
  exact: [],
  roles: [],
  sort: 'default',
  scope: 'all',
  visit: 'any',
  residenceRegionIds: [],
  workRegionIds: [],
  libraryIds: [],
  page: 1,
}

const BOOK = {
  id: 7,
  title: '사랑의 기술',
  authorDisplay: '에리히 프롬',
  publisher: '문예출판사',
  publishedOn: '2019-08-30',
  holdingLibraryCount: 3,
  joinTier: 'OPEN' as const,
  exactTitle: false,
}

function page(overrides: Partial<BookPage> = {}): BookPage {
  return {
    items: [BOOK],
    page: 1,
    pageSize: 20,
    totalCount: 30,
    hasMore: true,
    collectedFrom: '2026-10-01',
    collectedTo: '2026-10-05',
    ...overrides,
  }
}

const tooMany = () => new ApiError(429, { title: 'Too Many Requests', status: 429, code: 'too_many_requests' })
const busy = () => new ApiError(503, { title: 'Service Unavailable', status: 503, code: 'server_busy' })

function renderInstant(bookLinkSearch = 'scope=all') {
  render(
    <MemoryRouter>
      <InstantResults
        query={QUERY}
        filter="all"
        bookLinkSearch={bookLinkSearch}
        onFilterChange={vi.fn()}
        onSortChange={vi.fn()}
        onDirect={vi.fn()}
        onInvalidRegion={() => Promise.resolve(false)}
      />
    </MemoryRouter>,
  )
  return userEvent.setup()
}

const LIBRARY: TargetLibrary = {
  id: 1,
  name: '가 도서관',
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
}

/** A first stream that answers the book at 가 도서관 (holding 101) and ends. */
function answered(bookId: number, batchNo: number, holdingId = 101): HoldingStreamEvent[] {
  return [
    {
      name: 'targets',
      data: {
        filter: 'all',
        scope: 'all',
        visit: 'any',
        regionFilterApplied: false,
        libraries: [LIBRARY],
        holdings: [{ holdingId, bookId, libraryId: 1 }],
      },
    },
    { name: 'holding-requested', data: { batchNo, holdingId, bookId, libraryId: 1 } },
    {
      name: 'holding-result',
      data: {
        batchNo,
        holdingId,
        bookId,
        libraryId: 1,
        fetchedAt: '',
        cached: false,
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
          titleKey: `book:${bookId}`,
          availability: 'AVAILABLE',
          availabilityBasis: 'COUNT',
          detailUrl: null,
        },
      },
    },
    { name: 'done', data: { batchNo } },
  ]
}

function renderBookList() {
  render(
    <MemoryRouter>
      <BookList
        query={QUERY}
        bookLinkSearch=""
        onSortChange={vi.fn()}
        onDirect={vi.fn()}
        onInvalidRegion={() => Promise.resolve(false)}
      />
    </MemoryRouter>,
  )
  return userEvent.setup()
}

describe('429 and 503 notices (cycle 08 AC-3)', () => {
  beforeEach(() => {
    vi.mocked(fetchBooks).mockReset()
    vi.mocked(holdingStream).mockReset()
    vi.mocked(holdingBatchStream).mockReset()
  })

  it('reads the reason from the status only', () => {
    expect(blockedBy(tooMany())).toBe('too_many')
    expect(blockedBy(busy())).toBe('busy')
    expect(blockedBy(new ApiError(500, { title: 'x', status: 500, code: 'internal_error' }))).toBeNull()
    expect(blockedBy(new Error('offline'))).toBeNull()
  })

  it('a refused first page says why instead of the general failure', async () => {
    vi.mocked(fetchBooks).mockRejectedValue(tooMany())
    renderBookList()
    expect((await screen.findByRole('alert')).textContent).toBe(BLOCKED_TEXT.too_many)
    expect(screen.queryByText(/검색하지 못했어요/)).toBeNull()
  })

  it('a refused next page keeps the books, puts one line above them and offers no retry button', async () => {
    vi.mocked(fetchBooks).mockResolvedValueOnce(page()).mockRejectedValueOnce(busy())
    const user = renderBookList()
    await user.click(await screen.findByRole('button', { name: /더 보기/ }))

    const notice = await screen.findByText(BLOCKED_TEXT.busy)
    expect(screen.getByRole('link', { name: /사랑의 기술/ })).toBeTruthy()
    // the notice comes before the list
    expect(notice.compareDocumentPosition(screen.getByRole('link', { name: /사랑의 기술/ })) &
      Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.queryByText(/더 찾지 못했어요/)).toBeNull()
    expect(screen.queryByRole('button', { name: /다시/ })).toBeNull()
  })

  // cycle 28 (ADR-0173) turned the notice-only state into the book list with the retry button
  it('바로 보기: a refused first holding stream keeps the book list under the notice and its button (cycle 28 AC-1)', async () => {
    vi.mocked(fetchBooks).mockResolvedValue(page({ hasMore: false }))
    vi.mocked(holdingStream).mockRejectedValue(busy())
    renderInstant()
    const notice = await screen.findByText(BUSY_KEEP_LIST_TEXT)
    expect(screen.queryByText(BLOCKED_TEXT.busy)).toBeNull()
    expect(screen.queryByText('조건에 맞는 도서관이 없어요')).toBeNull()
    const link = screen.getByRole('link', { name: /사랑의 기술/ })
    expect(link.getAttribute('href')).toBe('/books/7?scope=all')
    expect(screen.getByText('보유 도서관 3곳')).toBeTruthy()
    expect(screen.getByRole('button', { name: BUSY_RETRY_LABEL })).toBeTruthy()
    // the notice comes before the list
    expect(notice.compareDocumentPosition(link) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(holdingStream).toHaveBeenCalledTimes(1)
  })

  it('the holding state keeps the reason until the next batch starts', () => {
    // (unchanged by cycle 28: the first-stream refusal flag lives in the hook, not in this state)
    const failed = holdingsReducer(holdingsReducer(EMPTY_HOLDINGS, { type: 'batch-start', batchNo: 1 }), {
      type: 'batch-failed',
      batchNo: 1,
      blocked: 'too_many',
    })
    expect(failed.blocked).toBe('too_many')
    expect(holdingsReducer(failed, { type: 'batch-start', batchNo: 2 }).blocked).toBeNull()
  })
})

/** Cycle 28 (ADR-0173): the first holding stream refused with 503 keeps the book list; 「대출 정보 다시 확인」 asks again. */
describe('busy first stream keeps the book list (cycle 28)', () => {
  beforeEach(() => {
    vi.mocked(fetchBooks).mockReset()
    vi.mocked(holdingStream).mockReset()
    vi.mocked(holdingBatchStream).mockReset()
  })

  it('(a) the button opens the same stream again and locks while the answer is awaited', async () => {
    vi.mocked(fetchBooks).mockResolvedValue(page({ hasMore: false }))
    vi.mocked(holdingStream).mockRejectedValueOnce(busy()).mockReturnValueOnce(new Promise(() => {}))
    const user = renderInstant()
    await user.click(await screen.findByRole('button', { name: BUSY_RETRY_LABEL }))

    expect(holdingStream).toHaveBeenCalledTimes(2)
    const [first, second] = vi.mocked(holdingStream).mock.calls
    expect(second[0]).toEqual(first[0])
    expect(second[0].bookIds).toEqual([7])
    expect(second[0].filter).toBe('all')
    expect(second[0].scope).toBe('all')
    expect(await screen.findByText(BUSY_CHECKING_TEXT)).toBeTruthy()
    expect(screen.queryByText(BUSY_KEEP_LIST_TEXT)).toBeNull()
    expect((screen.getByRole('button', { name: BUSY_RETRY_LABEL }) as HTMLButtonElement).disabled).toBe(true)
    // the book lines stay while it is checked
    expect(screen.getByRole('link', { name: /사랑의 기술/ })).toBeTruthy()
  })

  it('(b) refused again: the same lines and notice, and the button works again', async () => {
    vi.mocked(fetchBooks).mockResolvedValue(page({ hasMore: false }))
    vi.mocked(holdingStream).mockRejectedValue(busy())
    const user = renderInstant()
    await user.click(await screen.findByRole('button', { name: BUSY_RETRY_LABEL }))

    expect(await screen.findByText(BUSY_KEEP_LIST_TEXT)).toBeTruthy()
    expect(screen.getByRole('link', { name: /사랑의 기술/ })).toBeTruthy()
    const button = screen.getByRole('button', { name: BUSY_RETRY_LABEL }) as HTMLButtonElement
    expect(button.disabled).toBe(false)
    await user.click(button)
    expect(holdingStream).toHaveBeenCalledTimes(3)
    expect(screen.queryByText(BLOCKED_TEXT.busy)).toBeNull()
  })

  it('(c) answered on the retry: the usual results replace the lines and the notice', async () => {
    vi.mocked(fetchBooks).mockResolvedValue(page({ hasMore: false }))
    vi.mocked(holdingStream)
      .mockRejectedValueOnce(busy())
      .mockImplementationOnce(async (_params, onEvent) => {
        for (const event of answered(7, 2)) onEvent(event)
      })
    const user = renderInstant()
    await user.click(await screen.findByRole('button', { name: BUSY_RETRY_LABEL }))

    expect(await screen.findByText('가 도서관')).toBeTruthy()
    expect(screen.queryByText(BUSY_KEEP_LIST_TEXT)).toBeNull()
    expect(screen.queryByText(BUSY_CHECKING_TEXT)).toBeNull()
    expect(screen.queryByRole('button', { name: BUSY_RETRY_LABEL })).toBeNull()
    expect(screen.queryByRole('link', { name: '사랑의 기술 에리히 프롬 · 문예출판사 · 2019 보유 도서관 3곳' })).toBeNull()
    expect(screen.queryByText('보유 도서관 3곳')).toBeNull()
  })

  it('(d) a first stream refused with 429 keeps the notice-only state of cycle 08', async () => {
    vi.mocked(fetchBooks).mockResolvedValue(page({ hasMore: false }))
    vi.mocked(holdingStream).mockRejectedValue(tooMany())
    renderInstant()
    expect((await screen.findByRole('alert')).textContent).toBe(BLOCKED_TEXT.too_many)
    expect(screen.queryByRole('link', { name: /사랑의 기술/ })).toBeNull()
    expect(screen.queryByRole('button', { name: BUSY_RETRY_LABEL })).toBeNull()
  })

  it('(e) a refused "결과 더 보기" batch keeps the results and the cycle 08 line, with no retry button (AC-5)', async () => {
    vi.mocked(fetchBooks).mockResolvedValue(page({ hasMore: false }))
    vi.mocked(holdingStream).mockImplementation(async (_params, onEvent) => {
      // two holdings, one answered: "결과 더 보기" has something left to ask
      const events = answered(7, 1)
      const targets = events[0]
      if (targets.name === 'targets') {
        targets.data.libraries = [LIBRARY, { ...LIBRARY, id: 2, name: '나 도서관' }]
        targets.data.holdings = [
          { holdingId: 101, bookId: 7, libraryId: 1 },
          { holdingId: 102, bookId: 7, libraryId: 2 },
        ]
      }
      for (const event of events) onEvent(event)
    })
    vi.mocked(holdingBatchStream).mockRejectedValue(busy())
    const user = renderInstant()
    await user.click(await screen.findByRole('button', { name: /결과 더 보기/ }))

    expect(await screen.findByText(BLOCKED_TEXT.busy)).toBeTruthy()
    expect(screen.getByText('가 도서관')).toBeTruthy()
    expect(screen.queryByRole('button', { name: BUSY_RETRY_LABEL })).toBeNull()
    expect(screen.queryByText(BUSY_KEEP_LIST_TEXT)).toBeNull()
  })

  it('(f) a refused first stream after 「다음 책 N권 보기」 keeps the results and adds the button for that page', async () => {
    const second = { ...BOOK, id: 8, title: '사랑의 역사' }
    vi.mocked(fetchBooks)
      .mockResolvedValueOnce(page({ hasMore: true, totalCount: 2, pageSize: 1 }))
      .mockResolvedValueOnce(page({ items: [second], page: 2, hasMore: false, totalCount: 2, pageSize: 1 }))
    vi.mocked(holdingStream)
      .mockImplementationOnce(async (_params, onEvent) => {
        for (const event of answered(7, 1)) onEvent(event)
      })
      .mockRejectedValueOnce(busy())
      .mockReturnValueOnce(new Promise(() => {}))
    const user = renderInstant()
    await user.click(await screen.findByRole('button', { name: /다음 책 1권 보기/ }))

    expect(await screen.findByText(BLOCKED_TEXT.busy)).toBeTruthy()
    // the first page's results stay; the new book has no book line
    expect(screen.getByText('가 도서관')).toBeTruthy()
    expect(screen.queryByRole('link', { name: /사랑의 역사/ })).toBeNull()
    expect(screen.queryByText(BUSY_KEEP_LIST_TEXT)).toBeNull()
    expect(vi.mocked(holdingStream).mock.calls[1][0].bookIds).toEqual([8])

    await user.click(screen.getByRole('button', { name: BUSY_RETRY_LABEL }))
    expect(holdingStream).toHaveBeenCalledTimes(3)
    expect(vi.mocked(holdingStream).mock.calls[2][0].bookIds).toEqual([8])
    expect(await screen.findByText(BUSY_CHECKING_TEXT)).toBeTruthy()
  })
})
