import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { SortSelect } from './SortSelect'

beforeAll(() => {
  // Radix positions menus with ResizeObserver, which jsdom does not have
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

afterEach(() => {
  cleanup()
  delete (window as { matchMedia?: unknown }).matchMedia
})

function phoneWidth() {
  window.matchMedia = ((query: string) => ({
    matches: query === '(width <= 640px)',
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  })) as unknown as typeof window.matchMedia
}

describe('SortSelect (user request 2026-10-05: the "?" is inside the list)', () => {
  it('lists the orders, then opens the 기본 순서 explanation in place without closing', async () => {
    const user = userEvent.setup()
    render(<SortSelect value="default" onChange={() => {}} />)

    await user.click(screen.getByRole('button', { name: '정렬: 기본 순서' }))
    expect(screen.getAllByRole('menuitemradio').map((item) => item.textContent)).toEqual([
      '기본 순서',
      '출판일 최신순',
      '출판일 오래된순',
    ])
    // the check is an SVG now (cycle 10 icon check): it marks the chosen order
    expect(screen.getAllByRole('menuitemradio')[0].getAttribute('aria-checked')).toBe('true')
    expect(screen.getAllByRole('menuitemradio')[0].querySelector('svg')).not.toBeNull()
    expect(screen.queryByText('기본 순서는 이렇게 정해요')).toBeNull()

    await user.click(screen.getByRole('menuitem', { name: /정렬 기준 보기/ }))

    expect(screen.getByText('기본 순서는 이렇게 정해요')).toBeTruthy()
    expect(screen.getByRole('menu')).toBeTruthy()
  })

  it('choosing an order reports it', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<SortSelect value="default" onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: '정렬: 기본 순서' }))
    await user.click(screen.getByRole('menuitemradio', { name: '출판일 최신순' }))

    expect(onChange).toHaveBeenCalledWith('newest')
  })
})

describe('SortSelect on phones (user 2026-10-20: iPhone-like)', () => {
  it('opens a bottom sheet of orders; a tap chooses and closes', async () => {
    phoneWidth()
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(<SortSelect value="default" onChange={onChange} />)
    const trigger = screen.getByRole('button', { name: '정렬: 기본 순서' })
    expect(trigger.querySelector('svg')).toBeNull()
    await user.click(trigger)
    const sheet = screen.getByRole('dialog', { name: '정렬' })
    expect(screen.getByTestId('sheet-handle')).toBeTruthy()
    const radios = screen.getAllByRole('radio')
    expect(radios.map((r) => r.getAttribute('aria-checked'))).toEqual(['true', 'false', 'false'])
    await user.click(screen.getByRole('button', { name: /정렬 기준 보기/ }))
    expect(sheet.textContent).toContain('기본 순서는 이렇게 정해요')
    await user.click(radios[1])
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

