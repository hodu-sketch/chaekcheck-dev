import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { DEFAULT_SETTINGS } from './searchSettings'
import { SettingsPanel, type SettingsSection } from './SettingsPanel'

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
  it('phones: a sheet with a handle and the same rows in the same order', async () => {
    render(<SettingsPanel settings={DEFAULT_SETTINGS} onChange={vi.fn()} scopeSection={<p>범위</p>} />)
    await userEvent.setup().click(screen.getByRole('button', { name: '검색 설정' }))

    const sheet = screen.getByRole('dialog', { name: '검색 설정' })
    expect(screen.getByTestId('sheet-handle')).toBeTruthy()
    const rows = Array.from(sheet.querySelectorAll('button[aria-expanded]')).map((b) => b.textContent)
    expect(rows).toEqual(['찾을 도서관', '보기 방식바로 보기', '도서관에서 직접 찾기끔'])
  })

  it('phones: pulling the handle down closes the sheet; a short pull does not', async () => {
    render(<SettingsPanel settings={DEFAULT_SETTINGS} onChange={vi.fn()} scopeSection={<p>범위</p>} />)
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
    render(<SettingsPanel settings={DEFAULT_SETTINGS} onChange={vi.fn()} scopeSection={<p>범위</p>} />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '검색 설정' }))
    await user.click(within(screen.getByRole('dialog', { name: '검색 설정' })).getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('phones: back closes only the sheet', async () => {
    render(<SettingsPanel settings={DEFAULT_SETTINGS} onChange={vi.fn()} scopeSection={<p>범위</p>} />)
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
    render(<SettingsPanel settings={DEFAULT_SETTINGS} onChange={vi.fn()} scopeSection={<p>범위</p>} />)
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

/** Cycle 22 R2-1, R8-1: the phone chip opens the sheet on "찾을 도서관"; the wider window has the 방문 가입 빼기 row. */
describe('찾을 도서관 (cycle 22)', () => {
  function Controlled() {
    const [open, setOpen] = useState(false)
    const [section, setSection] = useState<SettingsSection>('')
    return (
      <>
        <button
          type="button"
          onClick={() => {
            setSection('scope')
            setOpen(true)
          }}
        >
          chip
        </button>
        <SettingsPanel
          settings={DEFAULT_SETTINGS}
          onChange={vi.fn()}
          scopeSection={<p>사는 곳 · 일하는 곳 · 직접 추가 · 빼기 · 전체 보기</p>}
          control={{ open, onOpenChange: setOpen, section, onSectionChange: setSection }}
        />
      </>
    )
  }

  it('phones: opened from outside on "찾을 도서관", first of the three rows, with its body', async () => {
    render(<Controlled />)
    await userEvent.setup().click(screen.getByRole('button', { name: 'chip' }))

    const sheet = screen.getByRole('dialog', { name: '검색 설정' })
    const rows = Array.from(sheet.querySelectorAll('button[aria-expanded]'))
    expect(rows.map((b) => b.textContent)).toEqual(['찾을 도서관', '보기 방식바로 보기', '도서관에서 직접 찾기끔'])
    expect(rows[0].getAttribute('aria-expanded')).toBe('true')
    expect(within(sheet).getByText('사는 곳 · 일하는 곳 · 직접 추가 · 빼기 · 전체 보기')).toBeTruthy()
  })

  it('wider screens: one more row under the two groups, the 방문 가입 빼기 switch, and no regions', async () => {
    render(
      <SettingsPanel
        settings={DEFAULT_SETTINGS}
        onChange={vi.fn()}
        visitSwitch={
          <label>
            <input type="checkbox" />
            직접 가야 가입되는 도서관은 빼기
          </label>
        }
      />,
    )
    await userEvent.setup().click(screen.getByRole('button', { name: '검색 설정' }))
    const window = screen.getByRole('dialog')
    const visit = within(window).getByRole('checkbox', { name: '직접 가야 가입되는 도서관은 빼기' })
    const direct = within(window).getByRole('radiogroup', { name: '도서관에서 직접 찾기' })
    expect(direct.compareDocumentPosition(visit) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(within(window).queryByText(/사는 곳/)).toBeNull()
  })
})
