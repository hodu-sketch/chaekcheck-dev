import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { DEFAULT_SETTINGS } from './searchSettings'
import { SettingsPanel } from './SettingsPanel'

afterEach(cleanup)

beforeAll(() => {
  // Radix positions popovers with ResizeObserver, which jsdom does not have
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

/** Cycle 08 (flow.md 검토 #1): phones get a bottom sheet with the same rows; wider screens keep the small window. */
describe('settings on phones and wider screens', () => {
  it('phones: a sheet with a handle and the same rows in the same order, without "찾을 도서관" (user 2026-10-19)', async () => {
    render(<SettingsPanel settings={DEFAULT_SETTINGS} onChange={vi.fn()} sheet />)
    await userEvent.setup().click(screen.getByRole('button', { name: '검색 설정' }))

    const sheet = screen.getByRole('dialog', { name: '검색 설정' })
    expect(screen.getByTestId('sheet-handle')).toBeTruthy()
    const rows = Array.from(sheet.querySelectorAll('button[aria-expanded]')).map((b) => b.textContent)
    expect(rows).toEqual(['보기 방식바로 보기', '도서관에서 직접 찾기끔'])
    expect(within(sheet).queryByText('찾을 도서관')).toBeNull()
    expect(within(sheet).queryByRole('checkbox')).toBeNull()
  })

  it('phones: pulling the handle down closes the sheet; a short pull does not', async () => {
    render(<SettingsPanel settings={DEFAULT_SETTINGS} onChange={vi.fn()} sheet />)
    await userEvent.setup().click(screen.getByRole('button', { name: '검색 설정' }))
    const handle = screen.getByTestId('sheet-handle')

    fireEvent.pointerDown(handle, { clientY: 500, pointerId: 1 })
    fireEvent.pointerMove(handle, { clientY: 540, pointerId: 1 })
    fireEvent.pointerUp(handle, { clientY: 540, pointerId: 1 })
    expect(screen.queryByRole('dialog')).toBeTruthy()

    fireEvent.pointerDown(handle, { clientY: 500, pointerId: 1 })
    fireEvent.pointerMove(handle, { clientY: 620, pointerId: 1 })
    fireEvent.pointerUp(handle, { clientY: 620, pointerId: 1 })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('phones: ⓧ closes the sheet, like the other phone sheets (plan request 2026-10-12)', async () => {
    render(<SettingsPanel settings={DEFAULT_SETTINGS} onChange={vi.fn()} sheet />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '검색 설정' }))
    await user.click(within(screen.getByRole('dialog', { name: '검색 설정' })).getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('phones: back closes only the sheet', async () => {
    render(<SettingsPanel settings={DEFAULT_SETTINGS} onChange={vi.fn()} sheet />)
    await userEvent.setup().click(screen.getByRole('button', { name: '검색 설정' }))
    await waitFor(() => expect(window.history.state).toHaveProperty('chaekcheckSheet'))
    window.history.back()
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getByRole('button', { name: '검색 설정' })).toBeTruthy()
  })

  it('wider screens: no ⓧ in the small window', async () => {
    render(<SettingsPanel settings={DEFAULT_SETTINGS} onChange={vi.fn()} />)
    await userEvent.setup().click(screen.getByRole('button', { name: '검색 설정' }))
    expect(screen.queryByRole('button', { name: '닫기' })).toBeNull()
  })

  it('phones: Escape closes the sheet', async () => {
    render(<SettingsPanel settings={DEFAULT_SETTINGS} onChange={vi.fn()} sheet />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '검색 설정' }))
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('wider screens: the small window, no handle', async () => {
    render(<SettingsPanel settings={DEFAULT_SETTINGS} onChange={vi.fn()} />)
    await userEvent.setup().click(screen.getByRole('button', { name: '검색 설정' }))
    expect(screen.getByRole('dialog')).toBeTruthy()
    expect(screen.queryByTestId('sheet-handle')).toBeNull()
    expect(screen.getByRole('radiogroup', { name: '보기 방식' })).toBeTruthy()
  })
})

/** User 2026-10-19: the 방문 가입 빼기 switch is only in the "찾을 도서관" box, not in the ⚙ window (cycle 22 R8-1 had it here). */
describe('찾을 도서관 (cycle 22)', () => {
  it('wider screens: the window has the two groups only, no 방문 가입 빼기 switch and no regions', async () => {
    render(<SettingsPanel settings={DEFAULT_SETTINGS} onChange={vi.fn()} />)
    await userEvent.setup().click(screen.getByRole('button', { name: '검색 설정' }))
    const window = screen.getByRole('dialog')
    expect(within(window).getByRole('radiogroup', { name: '도서관에서 직접 찾기' })).toBeTruthy()
    expect(within(window).queryByRole('switch', { name: '온라인에서 가입 가능한 곳만 보기' })).toBeNull()
    expect(within(window).queryByText(/사는 곳/)).toBeNull()
  })
})
