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

afterEach(cleanup)

describe('SortSelect (user request 2026-10-05: the "?" is inside the list)', () => {
  it('lists the orders, then opens the 기본 순서 explanation in place without closing', async () => {
    const user = userEvent.setup()
    render(<SortSelect value="default" onChange={() => {}} />)

    await user.click(screen.getByRole('button', { name: '정렬: 기본 순서' }))
    expect(screen.getAllByRole('menuitemradio').map((item) => item.textContent)).toEqual([
      '기본 순서✓',
      '출판일 최신순',
      '출판일 오래된순',
    ])
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
