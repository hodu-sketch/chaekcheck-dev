import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppLayout } from './AppLayout'
import { MENU_DELAY_MS } from './headerMenus'

vi.mock('../features/auth/AuthHeaderAction', () => ({ AuthHeaderAction: () => <button type="button">로그인</button> }))
vi.mock('../features/auth/NoticeContext', () => ({ NoticeBar: () => null }))
vi.mock('../features/mylibrary/RegionImportDialog', () => ({ RegionImportDialog: () => null }))

/** The lookup status answer (cycle 20); by default the ask never answers, so the header draws only the dot's place. */
const GREEN = JSON.stringify({ slots: 40, inUse: 0, level: 'GREEN', reason: 'NONE', queuedLibraryIds: [], serverTime: '' })

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => {})))
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="*" element={<Where />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function wide() {
  return screen.getAllByRole('navigation', { name: '주 메뉴' })[0]
}

function column(name: string) {
  return wide().querySelector<HTMLElement>(`[data-column="${name}"]`)!
}

/** Cycle 21 spec R1, AC-1, AC-3 (ADR-0127); cycle 14 AC-15 (no tagline, no "인기 도서"). */
describe('header', () => {
  beforeEach(() => vi.useFakeTimers())

  it('has the three big menus, no tagline and no old link', () => {
    renderAt('/')
    const nav = within(wide())
    expect(nav.getAllByRole('link').filter((a) => a.hasAttribute('data-big')).map((a) => a.textContent)).toEqual([
      '도서검색',
      '추천도서',
      '마이페이지',
    ])
    expect(nav.getByRole('link', { name: '추천도서' }).getAttribute('href')).toBe('/picks/leedongjin')
    expect(nav.getByRole('link', { name: '마이페이지' }).getAttribute('href')).toBe('/my-libraries')
    expect(screen.getByRole('button', { name: '로그인' })).toBeTruthy()
    expect(screen.queryByRole('link', { name: '사서추천도서' })).toBeNull()
    expect(screen.queryByText('전자도서관 대출 가능 확인')).toBeNull()
    expect(screen.queryByText(/인기 도서/)).toBeNull()
  })

  it('opens every column after resting 0.5s and closes 0.5s after leaving', () => { // AC-1, user 2026-10-16
    renderAt('/')
    const menu = wide().parentElement!
    const big = (name: string) => within(wide()).getByRole('link', { name }).parentElement!
    fireEvent.mouseEnter(big('추천도서'))
    act(() => vi.advanceTimersByTime(MENU_DELAY_MS - 1))
    expect(column('picks').hidden).toBe(true)
    act(() => vi.advanceTimersByTime(1))
    expect(column('picks').hidden).toBe(false)
    expect(column('my').hidden).toBe(false)
    expect(column('search').hidden).toBe(false)
    expect(within(column('picks')).getAllByRole('link').map((a) => a.textContent)).toEqual([
      '이동진 추천도서',
      '사서 추천도서',
      '서울대 100선',
    ])
    fireEvent.mouseEnter(big('마이페이지'))
    expect(column('picks').hidden).toBe(false)
    expect(column('my').hidden).toBe(false)
    expect(within(column('my')).getByRole('link', { name: '내 도서관 관리' }).getAttribute('href')).toBe(
      '/my-libraries',
    )

    fireEvent.mouseLeave(menu)
    act(() => vi.advanceTimersByTime(MENU_DELAY_MS - 1))
    expect(column('my').hidden).toBe(false)
    act(() => vi.advanceTimersByTime(1))
    expect(column('my').hidden).toBe(true)
  })

  it('does not open for a pointer passing by', () => {
    renderAt('/')
    const menu = wide().parentElement!
    fireEvent.mouseEnter(within(wide()).getByRole('link', { name: '추천도서' }).parentElement!)
    act(() => vi.advanceTimersByTime(300))
    fireEvent.mouseLeave(menu)
    act(() => vi.advanceTimersByTime(MENU_DELAY_MS * 2))
    expect(column('picks').hidden).toBe(true)
  })

  it('darkens nothing on wide screens', () => {
    renderAt('/')
    fireEvent.mouseEnter(within(wide()).getByRole('link', { name: '추천도서' }).parentElement!)
    act(() => vi.advanceTimersByTime(MENU_DELAY_MS))
    expect(screen.queryByTestId('menu-dim')).toBeNull()
  })

  it('opens with ↓ from a big menu and closes with Esc back on it', () => {
    renderAt('/')
    const big = within(wide()).getByRole('link', { name: '추천도서' })
    big.focus()
    fireEvent.keyDown(big, { key: 'ArrowDown' })
    // the first item is focused on the next frame (not runAllTimers: the status ask repeats every 10s)
    act(() => vi.advanceTimersByTime(100))
    expect(column('picks').hidden).toBe(false)
    const first = within(column('picks')).getByRole('link', { name: '이동진 추천도서' })
    first.focus()
    fireEvent.keyDown(first, { key: 'ArrowDown' })
    expect(document.activeElement?.textContent).toBe('사서 추천도서')
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
    expect(column('picks').hidden).toBe(true)
    expect(document.activeElement).toBe(big)
  })

  it('opens on a click without leaving the screen, and closes on a second click', () => { // user 2026-10-16
    renderAt('/')
    const big = within(wide()).getByRole('link', { name: '추천도서' })
    fireEvent.click(big)
    expect(column('picks').hidden).toBe(false)
    expect(column('search').hidden).toBe(false)
    expect(screen.getByTestId('where').textContent).toBe('/')
    fireEvent.click(big)
    expect(column('picks').hidden).toBe(true)
    expect(screen.getByTestId('where').textContent).toBe('/')
  })

  it('keeps them open on a click on another big menu', () => {
    renderAt('/')
    fireEvent.click(within(wide()).getByRole('link', { name: '추천도서' }))
    fireEvent.click(within(wide()).getByRole('link', { name: '마이페이지' }))
    expect(column('my').hidden).toBe(false)
    fireEvent.click(within(wide()).getByRole('link', { name: '마이페이지' }))
    expect(column('my').hidden).toBe(true)
  })

  it('keeps the columns open on a click after the pointer opened them', () => {
    renderAt('/')
    fireEvent.mouseEnter(within(wide()).getByRole('link', { name: '추천도서' }).parentElement!)
    act(() => vi.advanceTimersByTime(MENU_DELAY_MS))
    fireEvent.click(within(wide()).getByRole('link', { name: '추천도서' }))
    expect(column('picks').hidden).toBe(false)
  })

  it('goes to the main page on the logo and opens no menu', () => { // user 2026-10-16
    renderAt('/picks/snu100')
    fireEvent.click(screen.getByRole('link', { name: '책첵' }))
    expect(screen.getByTestId('where').textContent).toBe('/')
    expect(column('picks').hidden).toBe(true)
    expect(screen.queryByTestId('menu-dim')).toBeNull()
  })

  it('closes on a press elsewhere', () => {
    renderAt('/picks/snu100')
    fireEvent.click(within(wide()).getByRole('link', { name: '추천도서' }))
    expect(column('picks').hidden).toBe(false)
    fireEvent.pointerDown(screen.getByTestId('where'))
    expect(column('picks').hidden).toBe(true)
  })

  it('goes from an item and closes', () => {
    renderAt('/')
    fireEvent.click(within(wide()).getByRole('link', { name: '도서검색' }))
    fireEvent.click(within(column('search')).getByRole('link', { name: '전자책 검색' }))
    expect(screen.getByTestId('where').textContent).toBe('/search')
    expect(column('search').hidden).toBe(true)
  })

  it.each(['/', '/search', '/books/1'])('underlines 도서검색 on %s', (path) => { // user 2026-10-16
    renderAt(path)
    expect(within(wide()).getByRole('link', { name: '도서검색' }).hasAttribute('data-current')).toBe(true)
    expect(within(wide()).getByRole('link', { name: '추천도서' }).hasAttribute('data-current')).toBe(false)
  })

  it('underlines the big menu and bolds the list on screen', () => {
    renderAt('/picks/snu100')
    expect(within(wide()).getByRole('link', { name: '추천도서' }).hasAttribute('data-current')).toBe(true)
    expect(within(wide()).getByRole('link', { name: '마이페이지' }).hasAttribute('data-current')).toBe(false)
    expect(within(column('picks')).getByRole('link', { name: '서울대 100선', hidden: true }).getAttribute('aria-current')).toBe(
      'page',
    )
  })

  it.each(['/terms', '/privacy'])('is the same header on %s', (path) => { // AC-3; cycle 20 AC-1 (ADR-0121)
    const { container } = renderAt(path)
    expect(within(wide()).getByRole('link', { name: '추천도서' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '로그인' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '메뉴 열기' })).toBeTruthy()
    expect(screen.getByRole('link', { name: '책첵' })).toBeTruthy()
    expect(container.querySelector('[data-lookup-status-slot]')).toBeTruthy()
  })

  it('keeps the dot place left of 로그인 (phones: dot, then ▾, then 로그인), empty before the first answer', () => {
    // user 2026-10-17: "폰인데 이거 위치 서로 바꾸는 게 나은 것 같아" (the dot and the ▾ swapped)
    const { container } = renderAt('/')
    const slot = container.querySelector('[data-lookup-status-slot]')!
    const caret = slot.nextElementSibling!
    const login = screen.getByRole('button', { name: '로그인' })
    expect(caret.querySelector('svg')).toBeTruthy()
    expect(caret.compareDocumentPosition(login) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(slot.childElementCount).toBe(0)
  })
})

/**
 * Cycle 20 handoff 1-12 [확정] (plan 21 dev review #9): the dot opens the status popover and never the menus; the
 * menus and the popover never show together; Esc closes only the open one and returns the focus to its opener.
 */
describe('status dot and the menus', () => {
  async function renderGreen(path = '/') {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(GREEN, { status: 200 }))))
    const result = renderAt(path)
    await screen.findByRole('button', { name: '조회 상태: 원활' })
    return result
  }
  const dot = () => screen.getByRole('button', { name: '조회 상태: 원활' })

  it('the dot opens the popover and no menu, on phones too', async () => {
    await renderGreen()
    fireEvent.click(dot())
    expect(await screen.findByRole('dialog')).toBeTruthy()
    expect(column('picks').hidden).toBe(true)
    expect(screen.queryByTestId('menu-dim')).toBeNull()
  })

  it('the dot closes open menus, and opening the menus closes the popover', async () => {
    await renderGreen()
    fireEvent.click(within(wide()).getByRole('link', { name: '추천도서' }))
    expect(column('picks').hidden).toBe(false)
    fireEvent.click(dot())
    expect(await screen.findByRole('dialog')).toBeTruthy()
    expect(column('picks').hidden).toBe(true)

    fireEvent.click(within(wide()).getByRole('link', { name: '추천도서' }))
    expect(column('picks').hidden).toBe(false)
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('the phone menu closes the popover and the dot closes the phone menu', async () => {
    await renderGreen()
    fireEvent.click(dot())
    expect(await screen.findByRole('dialog')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '메뉴 열기' }))
    expect(screen.getByTestId('menu-dim')).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.click(dot())
    expect(await screen.findByRole('dialog')).toBeTruthy()
    expect(screen.queryByTestId('menu-dim')).toBeNull()
  })

  it('Esc closes the popover alone and returns the focus to the dot', async () => {
    await renderGreen()
    dot().focus()
    fireEvent.click(dot())
    const popover = await screen.findByRole('dialog')
    fireEvent.keyDown(popover, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    // Radix returns the focus on the next tick
    await waitFor(() => expect(document.activeElement).toBe(dot()))
    expect(screen.queryByTestId('menu-dim')).toBeNull()
  })
})

/**
 * Spec R1-5, AC-2, user 2026-10-16: the header row itself opens the menus with the screen darkened; the row again,
 * the dark part, Esc or an item closes them.
 */
describe('phone menu', () => {
  function openPhone() {
    fireEvent.click(screen.getByRole('button', { name: '메뉴 열기' }))
    return screen.getAllByRole('navigation', { name: '주 메뉴' })[1]
  }

  it('opens the same menus and closes when the row is tapped again', () => {
    renderAt('/')
    const drawer = openPhone()
    expect(within(drawer).getAllByRole('link').map((a) => a.textContent)).toEqual([
      '전자책 검색',
      '이동진 추천도서',
      '사서 추천도서',
      '서울대 100선',
      '내 도서관 관리',
      '계정',
    ])
    expect(screen.getByTestId('menu-dim')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '메뉴 닫기' }))
    expect(screen.queryByTestId('menu-dim')).toBeNull()
  })

  it('closes on the dark part and on Esc', () => {
    renderAt('/')
    openPhone()
    fireEvent.click(screen.getByTestId('menu-dim'))
    expect(screen.getAllByRole('navigation', { name: '주 메뉴' })).toHaveLength(1)
    openPhone()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.getAllByRole('navigation', { name: '주 메뉴' })).toHaveLength(1)
  })

  it('goes and closes on an item, with the list on screen in bold', () => {
    renderAt('/picks/librarian')
    const drawer = openPhone()
    expect(within(drawer).getByRole('link', { name: '사서 추천도서' }).getAttribute('aria-current')).toBe('page')
    fireEvent.click(within(drawer).getByRole('link', { name: '서울대 100선' }))
    expect(screen.getByTestId('where').textContent).toBe('/picks/snu100')
    expect(screen.queryByTestId('menu-dim')).toBeNull()
  })
})
