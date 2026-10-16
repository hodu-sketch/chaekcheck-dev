import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppLayout } from './AppLayout'
import { MENU_DELAY_MS } from './HeaderMenu'

vi.mock('../features/auth/AuthHeaderAction', () => ({ AuthHeaderAction: () => <button type="button">로그인</button> }))
vi.mock('../features/auth/NoticeContext', () => ({ NoticeBar: () => null }))
vi.mock('../features/mylibrary/RegionImportDialog', () => ({ RegionImportDialog: () => null }))

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="*" element={<Where />} />
        </Route>
      </Routes>
    </MemoryRouter>,
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

  it('has the two big menus going to their first items, no tagline and no old link', () => {
    renderAt('/')
    const nav = within(wide())
    expect(nav.getByRole('link', { name: '추천도서' }).getAttribute('href')).toBe('/picks/leedongjin')
    expect(nav.getByRole('link', { name: '마이페이지' }).getAttribute('href')).toBe('/my-libraries')
    expect(screen.getByRole('button', { name: '로그인' })).toBeTruthy()
    expect(screen.queryByRole('link', { name: '사서추천도서' })).toBeNull()
    expect(screen.queryByText('전자도서관 대출 가능 확인')).toBeNull()
    expect(screen.queryByText(/인기 도서/)).toBeNull()
  })

  it('opens both columns after resting 0.5s and closes 0.5s after leaving', () => { // AC-1
    renderAt('/')
    const menu = wide().parentElement!
    fireEvent.mouseEnter(menu)
    act(() => vi.advanceTimersByTime(MENU_DELAY_MS - 1))
    expect(column('picks').hidden).toBe(true)
    act(() => vi.advanceTimersByTime(1))
    expect(column('picks').hidden).toBe(false)
    expect(column('my').hidden).toBe(false)
    expect(within(column('picks')).getAllByRole('link').map((a) => a.textContent)).toEqual([
      '이동진 추천도서',
      '사서 추천도서',
      '서울대 100선',
    ])
    expect(within(column('my')).getByRole('link', { name: '내 도서관 관리' }).getAttribute('href')).toBe(
      '/my-libraries',
    )

    fireEvent.mouseLeave(menu)
    act(() => vi.advanceTimersByTime(MENU_DELAY_MS - 1))
    expect(column('picks').hidden).toBe(false)
    act(() => vi.advanceTimersByTime(1))
    expect(column('picks').hidden).toBe(true)
  })

  it('does not open for a pointer passing by', () => {
    renderAt('/')
    const menu = wide().parentElement!
    fireEvent.mouseEnter(menu)
    act(() => vi.advanceTimersByTime(300))
    fireEvent.mouseLeave(menu)
    act(() => vi.advanceTimersByTime(MENU_DELAY_MS * 2))
    expect(column('picks').hidden).toBe(true)
  })

  it('darkens nothing on wide screens', () => {
    renderAt('/')
    fireEvent.mouseEnter(wide().parentElement!)
    act(() => vi.advanceTimersByTime(MENU_DELAY_MS))
    expect(screen.queryByTestId('menu-dim')).toBeNull()
  })

  it('opens with ↓ from a big menu and closes with Esc back on it', () => {
    renderAt('/')
    const big = within(wide()).getByRole('link', { name: '추천도서' })
    big.focus()
    fireEvent.keyDown(big, { key: 'ArrowDown' })
    act(() => vi.runAllTimers())
    expect(column('picks').hidden).toBe(false)
    const first = within(column('picks')).getByRole('link', { name: '이동진 추천도서' })
    first.focus()
    fireEvent.keyDown(first, { key: 'ArrowDown' })
    expect(document.activeElement?.textContent).toBe('사서 추천도서')
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
    expect(column('picks').hidden).toBe(true)
    expect(document.activeElement).toBe(big)
  })

  it('opens on the first tap on touch screens, then goes', () => { // R1-5: 641px and up, tablets
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query === '(pointer: coarse)', media: query }))
    renderAt('/')
    const big = within(wide()).getByRole('link', { name: '추천도서' })
    fireEvent.click(big)
    expect(column('picks').hidden).toBe(false)
    expect(screen.getByTestId('where').textContent).toBe('/')
    fireEvent.click(big)
    expect(screen.getByTestId('where').textContent).toBe('/picks/leedongjin')
  })

  it('underlines the big menu and bolds the list on screen', () => {
    renderAt('/picks/snu100')
    expect(within(wide()).getByRole('link', { name: '추천도서' }).hasAttribute('data-current')).toBe(true)
    expect(within(wide()).getByRole('link', { name: '마이페이지' }).hasAttribute('data-current')).toBe(false)
    expect(within(column('picks')).getByRole('link', { name: '서울대 100선', hidden: true }).getAttribute('aria-current')).toBe(
      'page',
    )
  })

  it.each(['/terms', '/privacy'])('is the same header on %s', (path) => { // AC-3
    renderAt(path)
    expect(within(wide()).getByRole('link', { name: '추천도서' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '로그인' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '메뉴 열기' })).toBeTruthy()
    expect(screen.getByRole('link', { name: '책첵' })).toBeTruthy()
  })

  it('keeps the dot place left of 로그인, and 로그인 left of ☰', () => {
    const { container } = renderAt('/')
    const slot = container.querySelector('[data-lookup-status-slot]')!
    const login = screen.getByRole('button', { name: '로그인' })
    const menuButton = screen.getByRole('button', { name: '메뉴 열기' })
    expect(slot.compareDocumentPosition(login) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(login.compareDocumentPosition(menuButton) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})

/** Spec R1-5, AC-2: ☰ opens the menus with the screen darkened; ✕, the dark part, Esc or an item closes them. */
describe('phone menu', () => {
  function openPhone() {
    fireEvent.click(screen.getByRole('button', { name: '메뉴 열기' }))
    return screen.getAllByRole('navigation', { name: '주 메뉴' })[1]
  }

  it('opens the same menus and closes with ✕', () => {
    renderAt('/')
    const drawer = openPhone()
    expect(within(drawer).getAllByRole('link').map((a) => a.textContent)).toEqual([
      '이동진 추천도서',
      '사서 추천도서',
      '서울대 100선',
      '내 도서관 관리',
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
