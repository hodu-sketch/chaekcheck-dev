import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { BookDetail, PickBook, Picks, PicksListKey } from '../../api/types'
import { sendUsageEvent } from '../../api/usageEvents'
import { PicksRoute } from './PicksPage'
import { picksList, videoUrl, YOUTUBE_TERMS_URL } from './picksLists'

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
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/picks/:listKey" element={<PicksRoute />} />
      </Routes>
    </MemoryRouter>,
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
  it('names only the four lists', () => {
    expect(picksList('kaist')?.name).toBe('KAIST 추천도서')
    expect(picksList('leedongjin')?.name).toBe('이동진 추천도서')
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

describe('Lee Dong-jin screen', () => {
  it('shows the list name, the latest first with no date under it, and the source with the terms link in 추천 기준과 출처 보기', async () => {
    const asked = stubFetch({ leedongjin: LEEDONGJIN })
    renderAt('/picks/leedongjin')

    const all = await screen.findByRole('region', { name: '전체' })
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('이동진 추천도서')
    expect(asked).toContain('/api/v1/picks/leedongjin')
    const cards = within(all).getAllByRole('button', { name: /전자책/ })
    // user 2026-10-16: "추천도서 각각에 날짜나 숫자 붙이지 마"
    expect(cards[0].getAttribute('aria-label')).toBe('히스토리아 비테이, 전자책 있음')
    expect(within(all).queryByText('26.06')).toBeNull()
    // AC-9
    // user 2026-10-17: no source line under the grid; it is in 추천 기준과 출처 보기 with the terms link
    const details = screen.getByText('추천 기준과 출처 보기').closest('details')!
    const source = within(details).getByText(/^출처: 유튜브/, { selector: 'li' })
    expect(within(source).getByRole('link', { name: 'YouTube 서비스 약관' }).getAttribute('href')).toBe(YOUTUBE_TERMS_URL)
    await waitFor(() =>
      expect(vi.mocked(sendUsageEvent)).toHaveBeenCalledWith({ type: 'picks_list_view', list: 'leedongjin', category: 'ALL' }),
    )
  })

  it('opens the book window with the recommendation line and [영상 보기] in a new tab (AC-8)', async () => {
    stubFetch({ leedongjin: LEEDONGJIN })
    const user = renderAt('/picks/leedongjin')
    const all = await screen.findByRole('region', { name: '전체' })

    await user.click(within(all).getByRole('button', { name: /히스토리아 비테이/ }))

    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('heading', { name: '히스토리아 비테이' })).toBeTruthy()
    expect(within(dialog).queryByText('2026년 6월 추천')).toBeNull()
    const video = within(dialog).getByRole('link', { name: '영상 보기' })
    expect(video.getAttribute('href')).toBe('https://www.youtube.com/watch?v=lMx0zEd1qtY')
    expect(video.getAttribute('target')).toBe('_blank')
    expect(video.getAttribute('rel')).toContain('noopener')
    expect(vi.mocked(sendUsageEvent)).toHaveBeenCalledWith({
      type: 'picks_book_view',
      list: 'leedongjin',
      category: 'science',
      bookId: 701,
    })
  })

  it('keeps [영상 보기] and the year line in the request notice of a book with no e-book (R6-2)', async () => {
    stubFetch({ leedongjin: LEEDONGJIN })
    const user = renderAt('/picks/leedongjin')
    const all = await screen.findByRole('region', { name: '전체' })

    await user.click(within(all).getByRole('button', { name: /맡겨진 소녀/ }))

    const dialog = screen.getByRole('dialog', { name: '전자책 신청 안내' })
    expect(within(dialog).queryByText(/올해의 책/)).toBeNull()
    expect(within(dialog).getByRole('link', { name: '영상 보기' }).getAttribute('href')).toBe(
      'https://www.youtube.com/watch?v=6fhNgxzKaLE',
    )
  })

  it('says the list is not ready, or did not load, in its own words (R7-1)', async () => {
    stubFetch({ leedongjin: { list: 'leedongjin', fetchedAt: null, categories: [] } })
    renderAt('/picks/leedongjin')
    expect(await screen.findByText('아직 이동진 추천도서를 준비하고 있어요')).toBeTruthy()
    cleanup()

    stubFetch({})
    renderAt('/picks/snu100')
    expect((await screen.findByRole('alert')).textContent).toBe('서울대 100선을 불러오지 못했어요. 잠시 뒤에 다시 해 주세요.')
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
    expect(screen.getByText('출처: 서울대학교 기초교육원 『권장도서 해제집』(서울대학교출판문화원)').closest('details')).not.toBeNull()
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
