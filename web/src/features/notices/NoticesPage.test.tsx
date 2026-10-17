import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { calledUrls, fakeServer, json, noticeList, problem } from '../sitecontent/testSiteContent'
import { NoticePage } from './NoticePage'
import { NoticesPage } from './NoticesPage'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function Where() {
  const location = useLocation()
  return <div data-testid="where">{location.pathname + location.search}</div>
}

function renderAt(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/notices" element={<NoticesPage />} />
          <Route path="/notices/:noticeId" element={<NoticePage />} />
        </Routes>
        <Where />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function phoneWidth(on: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: on && query.includes('640px'),
    media: query,
    addEventListener() {},
    removeEventListener() {},
  }))
}

const where = () => screen.getByTestId('where').textContent

describe('S16 notice list (cycle 16)', () => {
  it('shows pinned notices first, then the newest; a row is the title and the posting date (AC-1, AC-13)', async () => {
    fakeServer({
      '/api/v1/notices': () =>
        json(
          200,
          noticeList({
            pinned: [{ id: 2, title: '고정된 안내', postedOn: '2026-10-10' }],
            items: [
              { id: 9, title: '새 공지', postedOn: '2026-11-05' },
              { id: 7, title: '점검 안내(시간 바뀜)', postedOn: '2026-11-03' },
            ],
            totalCount: 2,
          }),
        ),
    })
    renderAt('/notices')

    expect(screen.getByRole('heading', { name: '공지사항' })).toBeTruthy()
    const rows = await screen.findAllByRole('listitem')
    expect(rows.map((row) => row.textContent)).toEqual([
      '고정고정된 안내2026-10-10',
      '새 공지2026-11-05',
      '점검 안내(시간 바뀜)2026-11-03',
    ])
    expect(within(rows[0]).getByText('고정')).toBeTruthy()
    expect(within(rows[1]).queryByText('고정')).toBeNull()
    // the list never says when a notice was edited
    expect(document.body.textContent).not.toContain('고침')
    expect(within(rows[1]).getByRole('link').getAttribute('href')).toBe('/notices/9')
    // one page: no page numbers
    expect(screen.queryByRole('navigation', { name: '쪽' })).toBeNull()
  })

  it('shows only what the server sends, so a hidden notice is not there (AC-5)', async () => {
    fakeServer({
      '/api/v1/notices': () => json(200, noticeList({ items: [{ id: 3, title: '보이는 공지', postedOn: '2026-10-20' }], totalCount: 1 })),
    })
    renderAt('/notices')

    expect(await screen.findAllByRole('listitem')).toHaveLength(1)
  })

  it('says there is no notice yet', async () => {
    fakeServer({ '/api/v1/notices': () => json(200, noticeList()) })
    renderAt('/notices')

    expect(await screen.findByText('아직 공지가 없어요')).toBeTruthy()
  })

  it('shows grey lines while loading', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => {})))
    renderAt('/notices')

    expect(screen.getByRole('status', { name: '불러오는 중' })).toBeTruthy()
  })

  it('a server error says so and 다시 시도 reads again', async () => {
    let calls = 0
    fakeServer({
      '/api/v1/notices': () =>
        ++calls <= 2
          ? problem(500, 'internal_error')
          : json(200, noticeList({ items: [{ id: 1, title: '첫 공지', postedOn: '2026-10-20' }], totalCount: 1 })),
    })
    renderAt('/notices')

    expect(await screen.findByText('글을 불러오지 못했어요', undefined, { timeout: 3000 })).toBeTruthy()
    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByText('첫 공지')).toBeTruthy()
  })

  it('PC and tablet: page numbers 1~10 without arrows; a number goes to ?page=n', async () => {
    phoneWidth(false)
    const fetchMock = fakeServer({
      '/api/v1/notices': () => json(200, noticeList({ items: [{ id: 30, title: '공지 30', postedOn: '2026-10-30' }], totalCount: 23 })),
      '/api/v1/notices?page=2': () =>
        json(200, noticeList({ items: [{ id: 20, title: '공지 20', postedOn: '2026-10-20' }], page: 2, totalCount: 23 })),
    })
    renderAt('/notices')

    const pages = await screen.findByRole('navigation', { name: '쪽' })
    expect(within(pages).getAllByRole('link').map((link) => link.textContent)).toEqual(['1', '2', '3'])
    expect(within(pages).getByRole('link', { name: '1' }).getAttribute('aria-current')).toBe('page')

    await userEvent.setup().click(within(pages).getByRole('link', { name: '2' }))
    expect(await screen.findByText('공지 20')).toBeTruthy()
    expect(where()).toBe('/notices?page=2')
    expect(calledUrls(fetchMock)).toContain('/api/v1/notices?page=2')
  })

  it('PC: past ten pages the numbers come in blocks with ‹ ›', async () => {
    phoneWidth(false)
    fakeServer({
      '/api/v1/notices?page=12': () => json(200, noticeList({ items: [{ id: 5, title: '공지 5', postedOn: '2026-10-05' }], page: 12, totalCount: 150 })),
    })
    renderAt('/notices?page=12')

    const pages = await screen.findByRole('navigation', { name: '쪽' })
    expect(within(pages).getAllByRole('link').map((link) => link.getAttribute('aria-label') ?? link.textContent)).toEqual([
      '이전 쪽', '11', '12', '13', '14', '15', '다음 쪽',
    ])
    expect(within(pages).getByRole('link', { name: '이전 쪽' }).getAttribute('href')).toBe('/notices?page=11')
    expect(within(pages).getByRole('link', { name: '다음 쪽' }).getAttribute('href')).toBe('/notices?page=13')
  })

  it('phone: ‹ › and five numbers', async () => {
    phoneWidth(true)
    fakeServer({
      '/api/v1/notices': () => json(200, noticeList({ items: [{ id: 5, title: '공지 5', postedOn: '2026-10-05' }], totalCount: 80 })),
    })
    renderAt('/notices')

    const pages = await screen.findByRole('navigation', { name: '쪽' })
    expect(within(pages).getAllByRole('link').map((link) => link.getAttribute('aria-label') ?? link.textContent)).toEqual([
      '이전 쪽', '1', '2', '3', '4', '5', '다음 쪽',
    ])
    expect(within(pages).getByRole('link', { name: '이전 쪽' }).getAttribute('aria-disabled')).toBe('true')
    expect(within(pages).getByRole('link', { name: '다음 쪽' }).getAttribute('href')).toBe('/notices?page=2')
  })

  it('a page past the last follows the page the server used', async () => {
    fakeServer({
      '/api/v1/notices?page=99': () =>
        json(200, noticeList({ items: [{ id: 3, title: '도서관 12곳을 더했어요', postedOn: '2026-10-20' }], page: 3, totalCount: 23 })),
      '/api/v1/notices?page=3': () =>
        json(200, noticeList({ items: [{ id: 3, title: '도서관 12곳을 더했어요', postedOn: '2026-10-20' }], page: 3, totalCount: 23 })),
    })
    renderAt('/notices?page=99')

    expect(await screen.findByText('도서관 12곳을 더했어요')).toBeTruthy()
    await waitFor(() => expect(where()).toBe('/notices?page=3'))
  })

  it('a notice opened from page 2 goes back to page 2 with 목록으로', async () => {
    fakeServer({
      '/api/v1/notices?page=2': () =>
        json(200, noticeList({ items: [{ id: 7, title: '점검 안내', postedOn: '2026-11-03' }], page: 2, totalCount: 13 })),
      '/api/v1/notices/7': () =>
        json(200, {
          id: 7,
          title: '점검 안내',
          postedOn: '2026-11-03',
          editedOn: null,
          version: { number: 1, effectiveOn: '2026-11-03', current: true, body: '점검이 있어요.' },
          versions: [{ number: 1, effectiveOn: '2026-11-03', supersededOn: null }],
        }),
    })
    renderAt('/notices?page=2')
    const user = userEvent.setup()

    await user.click(await screen.findByRole('link', { name: /점검 안내/ }))
    expect(await screen.findByText('점검이 있어요.')).toBeTruthy()
    expect(where()).toBe('/notices/7')

    await user.click(screen.getByRole('link', { name: '목록으로' }))
    expect(where()).toBe('/notices?page=2')
  })
})
