import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchBooks } from '../../api/client'
import { holdingStream, searchStream } from '../../api/searchStream'
import type { HoldingStreamEvent, TargetLibrary } from '../../api/types'
import { AuthProvider } from '../auth/AuthContext'
import { NoticeProvider } from '../auth/NoticeContext'
import { MyLibraryProvider } from '../mylibrary/MyLibraryContext'
import { REGION_STORAGE_KEY } from '../region/regionStorage'
import { RegionProvider } from '../region/RegionContext'
import { SearchPage } from './SearchPage'
import { loadPrefs, savePrefs } from './searchSettings'

/**
 * Cycle 22 step 4 on the search screen: the buttons inside the results search again at once (R8-3, D10) — the 전국
 * links with `scope=all`, [그 N곳 빼기] with `visit=none` (also kept for later searches), [다시 넣기] back.
 */

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

const TREE = [{ id: 11, name: '서울특별시', shortName: '서울', children: [{ id: 1168, name: '강남구' }] }]

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

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
  holdingLibraryCount: 2,
  joinTier: 'OPEN' as const,
  exactTitle: false,
}

/** Two libraries holding the book, both borrowable now; 도서관2 has to be visited to join. */
function answer() {
  vi.mocked(holdingStream).mockImplementation(async (_params, onEvent) => {
    const libraries = [target(1), target(2, { visitRequired: true })]
    const events: HoldingStreamEvent[] = [
      {
        name: 'targets',
        data: {
          filter: 'all',
          scope: 'joinable',
          visit: 'any',
          regionFilterApplied: false,
          libraries,
          holdings: libraries.map((l) => ({ holdingId: 100 + l.id, bookId: 7, libraryId: l.id })),
        },
      },
      ...libraries.map(
        (l): HoldingStreamEvent => ({
          name: 'holding-result',
          data: {
            batchNo: 1,
            holdingId: 100 + l.id,
            bookId: 7,
            libraryId: l.id,
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
        }),
      ),
      { name: 'done', data: { batchNo: 1 } },
    ]
    for (const event of events) onEvent(event)
  })
}

function renderSearch(address = '/search?title=사랑') {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <NoticeProvider>
          <MyLibraryProvider>
            <RegionProvider>
              <MemoryRouter initialEntries={[address]}>
                <Routes>
                  <Route path="/search" element={<SearchPage />} />
                </Routes>
              </MemoryRouter>
            </RegionProvider>
          </MyLibraryProvider>
        </NoticeProvider>
      </AuthProvider>
    </QueryClientProvider>,
  )
  return userEvent.setup()
}

const lastStream = () => vi.mocked(holdingStream).mock.calls.at(-1)![0]
const lastBooks = () => vi.mocked(fetchBooks).mock.calls.at(-1)![0]

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

beforeEach(() => {
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
  answer()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url === '/api/v1/me' || url === '/api/v1/auth/refresh') return json(401, {})
      if (url === '/api/v1/regions') return json(200, TREE)
      if (url.startsWith('/api/v1/regions/resolutions?ids=')) {
        const ids = url.split('=')[1].split(',').map(Number)
        return json(200, ids.map((id) => ({ id, status: 'ACTIVE', label: null, replacements: [] })))
      }
      throw new Error(`unexpected ${url}`)
    }),
  )
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('buttons inside the results (cycle 22 R8-3)', () => {
  it('[전국에서 더 찾기] searches again with scope=all at once; [내 도서관만] goes back', async () => {
    // 바로 보기 does not know its whole plan: the green line takes the kept number of 어디 살든 가입 libraries (R7-1)
    savePrefs({ openCount: 39 })
    const user = renderSearch()
    await screen.findByText(/사는 곳을 고르지 않아 어디 살든 가입되는 도서관 39곳에서 찾았어요/)
    expect(lastStream().scope).toBe('joinable')
    expect(screen.queryByText(/전국에서 찾는 중/)).toBeNull()

    await user.click(screen.getByRole('button', { name: '전국에서 더 찾기' }))
    await vi.waitFor(() => expect(lastStream().scope).toBe('all'))
    expect(lastBooks().scope).toBe('all')
    // the green line is not for a 전국 search; the line says it is one (R1-5), without a number it does not know
    expect(screen.queryByText(/사는 곳을 고르지 않아/)).toBeNull()
    expect(screen.getByText('전국에서 찾는 중')).toBeTruthy()

    await user.click(screen.getByRole('button', { name: '내 도서관만' }))
    await vi.waitFor(() => expect(lastStream().scope).toBe('joinable'))
    expect(screen.queryByText(/전국에서 찾는 중/)).toBeNull()
  })

  it('[그 1곳 빼기] searches again with visit=none and keeps it; [다시 넣기] puts it back', async () => {
    const user = renderSearch()
    await user.click(await screen.findByRole('button', { name: '그 1곳 빼기' }))
    await vi.waitFor(() => expect(lastStream().visit).toBe('none'))
    expect(loadPrefs().visit).toBe('none')

    await user.click(await screen.findByRole('button', { name: '다시 넣기' }))
    await vi.waitFor(() => expect(lastStream().visit).toBe('any'))
    expect(loadPrefs().visit).toBe('any')
    expect(await screen.findByRole('button', { name: '그 1곳 빼기' })).toBeTruthy()
  })

  it('an empty ① for a search with regions offers [전국에서 찾기], which searches with scope=all', async () => {
    localStorage.setItem(REGION_STORAGE_KEY, JSON.stringify({ residence: [1168], work: [] }))
    const user = renderSearch()
    expect(await screen.findByRole('heading', { name: '내 도서관에는 없어요 (0)' })).toBeTruthy()
    expect(lastStream().residenceRegionIds).toEqual([1168])
    await user.click(screen.getByRole('button', { name: '전국에서 찾기' }))
    await vi.waitFor(() => expect(lastStream().scope).toBe('all'))
  })
})

/** Cycle 22 D5, R3-3, R3-4: a search that knows its whole plan (the direct search's first stream) keeps its size. */
describe('the plan size kept for the line', () => {
  function plan(count: number) {
    vi.mocked(searchStream).mockReset()
    vi.mocked(searchStream).mockImplementation(async (params, onEvent) => {
      onEvent({
        name: 'targets',
        data: {
          title: '사랑',
          filter: 'all',
          scope: params.scope,
          visit: params.visit,
          regionFilterApplied: false,
          libraries: Array.from({ length: count }, (_, index) => target(index + 1)),
        },
      })
    })
  }

  it('with regions: planCount is kept (the folded line itself shows only the residence)', async () => {
    plan(3)
    localStorage.setItem(REGION_STORAGE_KEY, JSON.stringify({ residence: [1168], work: [] }))
    renderSearch('/search?title=사랑&direct=on')
    const line = await screen.findByRole('button', { name: /^내 도서관: / })
    await vi.waitFor(() => expect(line.textContent).toBe('거주 서울 강남구'))
    await vi.waitFor(() => expect(loadPrefs().planCount?.count).toBe(3))
    expect(loadPrefs().openCount).toBeNull()
  })

  it('without regions: also openCount; a 전국 search keeps nothing', async () => {
    plan(39)
    renderSearch('/search?title=사랑&direct=on')
    await vi.waitFor(() => expect(loadPrefs().openCount).toBe(39))
    cleanup()
    localStorage.clear()

    plan(376)
    renderSearch('/search?title=사랑&direct=on&scope=all')
    expect(await screen.findByText('전국에서 찾는 중 · 376곳')).toBeTruthy()
    expect(loadPrefs().planCount).toBeNull()
    expect(loadPrefs().openCount).toBeNull()
  })
})
