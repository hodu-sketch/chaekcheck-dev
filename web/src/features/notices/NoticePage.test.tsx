import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { calledUrls, fakeServer, json, noticeOf, problem } from '../sitecontent/testSiteContent'
import { NoticePage } from './NoticePage'

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
          <Route path="/notices/:noticeId" element={<NoticePage />} />
          <Route path="*" element={null} />
        </Routes>
        <Where />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const EDITED = noticeOf({
  title: '점검 안내(시간 바뀜)',
  editedOn: '2026-11-04',
  version: { number: 2, effectiveOn: '2026-11-04', current: true, body: '점검은 **오후 2시**예요.' },
  versions: [
    { number: 2, effectiveOn: '2026-11-04', supersededOn: null },
    { number: 1, effectiveOn: '2026-11-03', supersededOn: '2026-11-04' },
  ],
})

const where = () => screen.getByTestId('where').textContent

describe('S17 notice (cycle 16)', () => {
  it('shows the title, the posting date, the text and no picker for a notice never edited', async () => {
    fakeServer({ '/api/v1/notices/7': () => json(200, noticeOf()) })
    renderAt('/notices/7')

    expect(await screen.findByRole('heading', { name: '점검 안내' })).toBeTruthy()
    expect(screen.getByText('2026-11-03')).toBeTruthy()
    expect(screen.getByText('점검이 있어요.')).toBeTruthy()
    expect(screen.queryByRole('combobox')).toBeNull()
    expect(screen.queryByText('이전 버전')).toBeNull()
    expect(document.body.textContent).not.toContain('고침')
  })

  it('an edited notice says when it was edited and has the date picker (AC-13)', async () => {
    fakeServer({ '/api/v1/notices/7': () => json(200, EDITED) })
    renderAt('/notices/7')

    expect(await screen.findByText('2026-11-03 · 고침 2026-11-04')).toBeTruthy()
    expect(screen.getByText('오후 2시').tagName).toBe('STRONG')
    expect((screen.getByRole('combobox', { name: '적용 기간' }) as HTMLSelectElement).value).toBe('2')
  })

  it('an earlier version opened from the picker shows the small "이전 버전" mark', async () => {
    const fetchMock = fakeServer({
      '/api/v1/notices/7': () => json(200, EDITED),
      '/api/v1/notices/7?version=1': () =>
        json(200, {
          ...EDITED,
          title: '점검 안내',
          version: { number: 1, effectiveOn: '2026-11-03', current: false, body: '점검은 오전 10시예요.' },
        }),
    })
    renderAt('/notices/7')

    await userEvent.setup().selectOptions(await screen.findByRole('combobox'), '1')

    expect(await screen.findByText('점검은 오전 10시예요.')).toBeTruthy()
    expect(screen.getByText('이전 버전')).toBeTruthy()
    expect(where()).toBe('/notices/7?version=1')
    expect(calledUrls(fetchMock)).toContain('/api/v1/notices/7?version=1')
  })

  it('a hidden or missing notice is "찾을 수 없어요" with a link to the list (AC-5)', async () => {
    fakeServer({ '/api/v1/notices/8': () => problem(404, 'notice_not_found') })
    renderAt('/notices/8')

    expect(await screen.findByText('찾을 수 없어요')).toBeTruthy()
    expect(screen.getByRole('link', { name: '공지 목록 보기' }).getAttribute('href')).toBe('/notices')
  })

  it('a missing version is "찾을 수 없어요" with a link to the current version (AC-9)', async () => {
    fakeServer({ '/api/v1/notices/7?version=99': () => problem(404, 'version_not_found') })
    renderAt('/notices/7?version=99')

    expect(await screen.findByText('찾을 수 없어요')).toBeTruthy()
    expect(screen.getByRole('link', { name: '지금 버전 보기' }).getAttribute('href')).toBe('/notices/7')
  })

  it('an address that is not a number is "찾을 수 없어요" without asking the server', async () => {
    const fetchMock = fakeServer({})
    renderAt('/notices/abc')

    expect(await screen.findByText('찾을 수 없어요')).toBeTruthy()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('shows grey lines while loading and a retry after a server error', async () => {
    let calls = 0
    fakeServer({ '/api/v1/notices/7': () => (++calls <= 2 ? problem(503, 'internal_error') : json(200, noticeOf())) })
    renderAt('/notices/7')

    expect(screen.getByRole('status', { name: '불러오는 중' })).toBeTruthy()
    expect(await screen.findByText('글을 불러오지 못했어요', undefined, { timeout: 3000 })).toBeTruthy()
    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByText('점검이 있어요.')).toBeTruthy()
  })

  it('목록으로 goes to the first page when the notice was opened directly', async () => {
    fakeServer({ '/api/v1/notices/7': () => json(200, noticeOf()) })
    renderAt('/notices/7')

    await screen.findByText('점검이 있어요.')
    await userEvent.setup().click(screen.getByRole('link', { name: '목록으로' }))
    expect(where()).toBe('/notices')
  })
})
