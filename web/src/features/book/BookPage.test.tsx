import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, fetchBook } from '../../api/client'
import { holdingStream } from '../../api/searchStream'
import type { HoldingStreamEvent, TargetLibrary } from '../../api/types'
import { BookPage } from './BookPage'

/** The place names of the regions are read through React Query. */
function withQuery(children: ReactNode) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{children}</QueryClientProvider>
}

vi.mock('../../api/searchStream', () => ({ holdingStream: vi.fn(), holdingBatchStream: vi.fn() }))

vi.mock('../../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/client')>()),
  fetchBook: vi.fn(),
}))
vi.mock('../region/RegionContext', () => ({
  useRegions: () => ({ regions: { residence: [], work: [] }, libraryIds: [], ready: true }),
}))

afterEach(cleanup)

/** Cycle 08 (R7, ADR-0070, AC-8c): a book id that an import of the libraries' lists may have dropped. */
describe('book that is not found', () => {
  it('explains that the lists were renewed and leads back to the start', async () => {
    vi.mocked(fetchBook).mockRejectedValue(
      new ApiError(404, { title: 'Not Found', status: 404, code: 'book_not_found' }),
    )
    render(withQuery(
      <MemoryRouter initialEntries={['/books/9999999']}>
        <Routes>
          <Route path="/books/:bookId" element={<BookPage />} />
        </Routes>
      </MemoryRouter>),
    )
    expect(await screen.findByText('책을 찾을 수 없어요')).toBeTruthy()
    expect(screen.getByText('도서관 목록을 새로 받으면서 빠진 책일 수 있어요. 다시 검색해 주세요.')).toBeTruthy()
    expect(screen.getByRole('link', { name: '처음으로' }).getAttribute('href')).toBe('/')
  })
})

/** Cycle 22 (R4-1, D9): no switch; the book screen's plan is joinable unless the address has scope=all. */
describe('scope', () => {
  const plan = () => vi.mocked(fetchBook).mock.calls.at(-1)![1]

  function renderAt(address: string) {
    vi.mocked(fetchBook).mockReset()
    vi.mocked(fetchBook).mockReturnValue(new Promise(() => {}))
    render(withQuery(
      <MemoryRouter initialEntries={[address]}>
        <Routes>
          <Route path="/books/:bookId" element={<BookPage />} />
        </Routes>
      </MemoryRouter>),
    )
  }

  it('is joinable without a stored switch value, and with an old one', async () => {
    renderAt('/books/5001')
    await vi.waitFor(() => expect(plan().scope).toBe('joinable'))
    cleanup()

    localStorage.setItem('chaekcheck.searchSettings.v1', JSON.stringify({ guestScope: 'all', myScope: 'all' }))
    renderAt('/books/5001')
    await vi.waitFor(() => expect(plan().scope).toBe('joinable'))
    localStorage.clear()
  })

  it('is all with scope=all in the address', async () => {
    renderAt('/books/5001?scope=all')
    await vi.waitFor(() => expect(plan().scope).toBe('all'))
  })
})

/** Cycle 22 (D9, AC-17): the book page keeps its list as it was, without result groups or pills. */
describe('holdings list', () => {
  it('has the result groups and pills of 바로 보기 (user 2026-10-19)', async () => {
    const library: TargetLibrary = {
      id: 1,
      name: '가 도서관',
      libraryType: 'EBOOK',
      platformType: 'KYOBO_V1',
      priorityGroup: 'OPEN',
      deferred: false,
      extraConditionText: null,
      visitRequired: true,
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
    vi.mocked(fetchBook).mockReset()
    vi.mocked(fetchBook).mockResolvedValue({
      id: 5001,
      title: '책',
      authorDisplay: null,
      publisher: null,
      publishedOn: null,
      isbn13: null,
      holdingLibraryCount: 1,
      libraries: [],
    })
    vi.mocked(holdingStream).mockImplementation(async (_params, onEvent) => {
      const events: HoldingStreamEvent[] = [
        {
          name: 'targets',
          data: {
            filter: 'all',
            scope: 'joinable',
            visit: 'any',
            regionFilterApplied: false,
            libraries: [library],
            holdings: [{ holdingId: 101, bookId: 5001, libraryId: 1 }],
          },
        },
        {
          name: 'holding-result',
          data: {
            batchNo: 1,
            holdingId: 101,
            bookId: 5001,
            libraryId: 1,
            item: {
              rank: 0,
              title: '책',
              author: null,
              publisher: null,
              supplier: null,
              holdings: 1,
              loaned: 0,
              reservations: 0,
              available: 1,
              titleKey: 'book:5001',
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
    })
    render(withQuery(
      <MemoryRouter initialEntries={['/books/5001']}>
        <Routes>
          <Route path="/books/:bookId" element={<BookPage />} />
        </Routes>
      </MemoryRouter>),
    )
    expect(await screen.findByRole('heading', { name: '지금 빌릴 수 있어요 (1)' })).toBeTruthy()
    expect(screen.getByText(/어디 살든 가입되는 도서관에 있어요/)).toBeTruthy()
    expect(screen.getByText('방문 가입').getAttribute('data-tone')).toBe('visit')
    expect(screen.queryByRole('button', { name: '전국에서 더 찾기' })).toBeNull()

    // the library name opens the join guide window here too (user 2026-10-19)
    await userEvent.setup().click(screen.getByRole('button', { name: '가 도서관 가입 정보' }))
    const guide = screen.getByRole('dialog', { name: '가 도서관 가입 안내' })
    expect(guide.textContent).toContain('가입 대상어디 살든 가입방문 가입')
    expect(guide.textContent).toContain('온라인 가입 후 도서관 방문')
  })
})
