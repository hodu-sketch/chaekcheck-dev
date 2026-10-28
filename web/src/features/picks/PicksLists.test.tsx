import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { BookDetail, PickBook, Picks, PicksListKey } from '../../api/types'
import { sendUsageEvent } from '../../api/usageEvents'
import { PicksRoute } from './PicksPage'
import { picksList, videoUrl } from './picksLists'

const REGIONS = {
  regions: { residence: [1116], work: [] },
  libraryIds: [12, 34],
  ready: true,
  source: 'browser',
  resolveStoredRegions: () => Promise.resolve(false),
}
vi.mock('../region/RegionContext', () => ({ useRegions: () => REGIONS }))
vi.mock('../../api/usageEvents', () => ({ sendUsageEvent: vi.fn() }))

const DETAIL: BookDetail = {
  id: 701,
  title: '햄릿(Hamlet)',
  authorDisplay: '셰익스피어',
  publisher: '민음사',
  publishedOn: '2001-01-01',
  isbn13: '9780000000002',
  holdingLibraryCount: 0,
  coverUrl: null,
  summary: null,
  libraries: [],
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

function book(overrides: Partial<PickBook>): PickBook {
  return {
    id: '1',
    category: 'x',
    title: '책',
    listTitle: null,
    coverUrl: null,
    bookId: 701,
    available: true,
    recommendedMonth: null,
    yearBook: null,
    videoId: null,
    tocNo: null,
    ...overrides,
  }
}

const LEEDONGJIN: Picks = {
  list: 'leedongjin',
  fetchedAt: null,
  categories: [
    {
      code: 'ALL',
      name: '전체',
      books: [
        book({ id: '12', category: 'science', title: '히스토리아 비테이', recommendedMonth: '2026-06', videoId: 'lMx0zEd1qtY' }),
        book({
          id: '11',
          category: 'foreign_fiction',
          title: '맡겨진 소녀',
          recommendedMonth: '2025-12',
          yearBook: 2025,
          videoId: '6fhNgxzKaLE',
          available: false,
          bookId: null,
        }),
      ],
    },
  ],
}

const SNU: Picks = {
  list: 'snu100',
  fetchedAt: null,
  categories: [
    {
      code: 'ALL',
      name: '전체',
      books: [
        book({ id: '3', category: 'korean_lit', title: '구운몽', tocNo: 3 }),
        book({ id: '23', category: 'foreign_lit', title: '햄릿', listTitle: '셰익스피어: 햄릿 외 3편', tocNo: 23 }),
        book({ id: '17', category: 'korean_lit', title: '광장', tocNo: 17, bookId: null, available: false }),
      ],
    },
  ],
}

function stubFetch(answers: Partial<Record<PicksListKey, Picks>>) {
  const asked: string[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      const url = new URL(input, 'http://localhost')
      asked.push(url.pathname)
      const list = url.pathname.match(/^\/api\/v1\/picks\/(\w+)$/)?.[1] as PicksListKey | undefined
      if (list && answers[list]) return json(answers[list])
      if (url.pathname.startsWith('/api/v1/books/')) return json(DETAIL)
      return json({ title: 'Bad Request', status: 400, code: 'validation_error' }, 400)
    }),
  )
  return asked
}

function renderAt(path: string) {
  render(
    // the condition card reads the region names (user 2026-10-20)
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/picks/:listKey" element={<PicksRoute />} />
          <Route path="/picks/for-you" element={<p>책첵 추천 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return userEvent.setup()
}

beforeAll(() => {
  globalThis.IntersectionObserver ??= class {
    observe() {}
    disconnect() {}
    unobserve() {}
    takeRecords() {
      return []
    }
    root = null
    rootMargin = ''
    thresholds = []
  } as unknown as typeof IntersectionObserver
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.mocked(sendUsageEvent).mockClear()
  window.history.replaceState(null, '')
})

/** Cycle 21 spec R5, R6, R7 (AC-4, AC-5, AC-8, AC-9, AC-15, AC-16). */
describe('the lists words', () => {
  it('names only the five lists', () => {
    expect(picksList('kaist')?.name).toBe('KAIST 사서 추천도서')
    // user 2026-10-20: the Bokklubben World Library, 노벨연구소 on its tab
    expect(picksList('nobel')?.name).toBe('노벨연구소 선정 최고의 책')
    expect(picksList('nobel')?.tabLabel).toBe('노벨연구소')
    expect(picksList('nobel')?.helpLines).toContain('책첵은 노벨연구소와 관계가 없어요')
    expect(picksList('nobel')?.source(null)).toContain('Bokklubben World Library')
    expect(picksList('nobel')?.yearLine).toBeUndefined()
    expect(picksList('toString')).toBeNull()
    // KAIST says the year, 국립중앙도서관 the year and month in the book windows (user 2026-10-20)
    expect(picksList('kaist')?.yearLine?.(book({ yearBook: 2025 }))).toBe('2025년 KAIST 사서추천')
    expect(picksList('librarian')?.yearLine?.(book({ recommendedMonth: '2026-10' }))).toBe('2026년 10월 국립중앙도서관 사서추천')
    expect(picksList('snu100')?.yearLine).toBeUndefined()
    // hidden from the screen since 2026-10-25
    expect(picksList('leedongjin')).toBeNull()
    expect(picksList('snu100')?.name).toBe('서울대 100선')
    expect(picksList('librarian')?.name).toBe('사서 추천도서')
    expect(picksList('yes24')).toBeNull()
    expect(picksList(undefined)).toBeNull()
  })

  it('builds the video address from the id only', () => {
    expect(videoUrl('lMx0zEd1qtY')).toBe('https://www.youtube.com/watch?v=lMx0zEd1qtY')
    expect(videoUrl('a b')).toBeNull()
    expect(videoUrl('x"><script>')).toBeNull()
    expect(videoUrl(null)).toBeNull()
  })
})

describe('Lee Dong-jin (hidden, user 2026-10-25: "이동진 안내는 전부 빼 논란이 있대")', () => {
  it('its address goes to 책첵 추천 and asks nothing', async () => {
    const asked = stubFetch({ leedongjin: LEEDONGJIN })
    renderAt('/picks/leedongjin')
    expect(await screen.findByText('책첵 추천 화면')).toBeTruthy()
    expect(asked).toEqual([])
  })
})

describe('SNU 100 screen', () => {
  it('keeps the table-of-contents order with no number under the covers, and shows the source (AC-4)', async () => {
    stubFetch({ snu100: SNU })
    renderAt('/picks/snu100')
    const all = await screen.findByRole('region', { name: '전체' })

    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('서울대 100선')
    expect(within(all).getAllByRole('button', { name: /전자책/ }).map((card) => card.getAttribute('aria-label'))).toEqual([
      '구운몽, 전자책 있음',
      '햄릿, 전자책 있음',
      '광장, 전자책 없음',
    ])
    // the source is only in 추천 기준과 출처 보기
    const source = screen.getByText(/^출처: 서울대학교 기초교육원 『권장도서 해제집』\(서울대학교출판문화원\)/, { selector: 'li' })
    expect(source.closest('details')).not.toBeNull()
    // the source's page (user 2026-10-20)
    expect(within(source).getByRole('link').getAttribute('href')).toBe(
      'https://www.snupress.com/book/category?md=view&goodsidx=2676',
    )
  })

  it('shows our title with no list name line, and no field or number (AC-8; AC-16 line taken out 2026-10-17)', async () => {
    stubFetch({ snu100: SNU })
    const user = renderAt('/picks/snu100')
    const all = await screen.findByRole('region', { name: '전체' })

    await user.click(within(all).getByRole('button', { name: /^햄릿/ }))

    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('heading', { name: '햄릿' })).toBeTruthy()
    expect(within(dialog).queryByText(/목록 이름/)).toBeNull()
    expect(within(dialog).queryByText('서울대 권장도서 100선')).toBeNull()
    expect(within(dialog).queryByText(/한국문학|외국문학|23/)).toBeNull()
    expect(within(dialog).queryByRole('link', { name: '영상 보기' })).toBeNull()
  })

  it('opens the request notice for a book of ours not found (AC-15)', async () => {
    stubFetch({ snu100: SNU })
    const user = renderAt('/picks/snu100')
    const all = await screen.findByRole('region', { name: '전체' })

    await user.click(within(all).getByRole('button', { name: /^광장/ }))

    expect(within(screen.getByRole('dialog', { name: '전자책 신청 안내' })).getByText('광장')).toBeTruthy()
  })
})

describe('the address', () => {
  it('shows nothing for a name that is not a list', () => {
    const asked = stubFetch({})
    renderAt('/picks/yes24')
    expect(screen.queryByRole('heading')).toBeNull()
    expect(asked).toEqual([])
  })
})
