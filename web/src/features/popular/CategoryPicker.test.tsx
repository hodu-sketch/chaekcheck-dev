import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, beforeAll, describe, expect, it } from 'vitest'
import { CategoryPicker } from './CategoryPicker'
import { CATEGORY_NAMES, testCategories } from './testPopular'

afterEach(cleanup)

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

function Harness() {
  const categories = testCategories()
  const [current, setCurrent] = useState('ALL')
  return (
    <CategoryPicker
      categories={categories}
      current={current}
      onPick={setCurrent}
      phone={false}
      month="2026-09"
      fetchedAt="2026-10-03T05:01:32+09:00"
    />
  )
}

/** S13c (R5-1, R5-2, AC-5). */
describe('category list', () => {
  it('lists the 17 categories in screen order, the current one marked', async () => {
    render(<Harness />)
    await userEvent.setup().click(screen.getByRole('button', { name: '분류: 전체' }))
    const items = within(screen.getByRole('list', { name: '분류' })).getAllByRole('button')
    expect(items.map((b) => b.textContent)).toEqual(CATEGORY_NAMES)
    expect(items[0].getAttribute('aria-current')).toBe('true')
    expect(items.filter((b) => b.hasAttribute('aria-current'))).toHaveLength(1)
  })

  it('picking 역사 closes the list and changes the box', async () => {
    render(<Harness />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '분류: 전체' }))
    await user.click(screen.getByRole('button', { name: '역사' }))
    expect(screen.queryByRole('list', { name: '분류' })).toBeNull()
    expect(screen.getByRole('button', { name: '분류: 역사' }).textContent).toContain('역사')
  })

  it('"순위 기준 보기" opens the explanation in the list without closing it (S13i)', async () => {
    render(<Harness />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '분류: 전체' }))
    const help = screen.getByRole('button', { name: '순위 기준 보기' })
    expect(help.getAttribute('aria-expanded')).toBe('false')
    await user.click(help)
    expect(help.getAttribute('aria-expanded')).toBe('true')
    expect(screen.getByRole('list', { name: '분류' })).toBeTruthy()
    expect(screen.getByText('흐린 책은 검색하는 도서관에 전자책이 없어요')).toBeTruthy()
    expect(screen.getByText('2026년 9월 판매 순위 · 10월 3일 받음')).toBeTruthy()
    await user.click(help)
    expect(screen.queryByText('흐린 책은 검색하는 도서관에 전자책이 없어요')).toBeNull()
  })

  it('Escape closes it on PC', async () => {
    render(<Harness />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '분류: 전체' }))
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('list', { name: '분류' })).toBeNull()
  })
})
