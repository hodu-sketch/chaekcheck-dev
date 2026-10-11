import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, fetchBooks, type BookQuery } from '../../api/client'
import { holdingBatchStream, holdingStream } from '../../api/searchStream'
import type { BookPage } from '../../api/types'
import { BLOCKED_TEXT, blockedBy } from './blocked'
import { BookList } from './BookList'
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

  it('바로 보기: a refused holding stream shows the busy line', async () => {
    vi.mocked(fetchBooks).mockResolvedValue(page({ hasMore: false }))
    vi.mocked(holdingStream).mockRejectedValue(busy())
    render(
      <MemoryRouter>
        <InstantResults
          query={QUERY}
          filter="all"
          onFilterChange={vi.fn()}
          onSortChange={vi.fn()}
          onDirect={vi.fn()}
          onInvalidRegion={() => Promise.resolve(false)}
        />
      </MemoryRouter>,
    )
    expect(await screen.findByText(BLOCKED_TEXT.busy)).toBeTruthy()
    expect(screen.queryByText('조건에 맞는 도서관이 없어요')).toBeNull()
  })

  it('the holding state keeps the reason until the next batch starts', () => {
    const failed = holdingsReducer(holdingsReducer(EMPTY_HOLDINGS, { type: 'batch-start', batchNo: 1 }), {
      type: 'batch-failed',
      batchNo: 1,
      blocked: 'too_many',
    })
    expect(failed.blocked).toBe('too_many')
    expect(holdingsReducer(failed, { type: 'batch-start', batchNo: 2 }).blocked).toBeNull()
  })
})
