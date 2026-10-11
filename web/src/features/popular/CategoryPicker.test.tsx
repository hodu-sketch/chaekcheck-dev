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
  return <CategoryPicker categories={categories} current={current} onPick={setCurrent} phone={false} />
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

  it('Escape closes it on PC', async () => {
    render(<Harness />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '분류: 전체' }))
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('list', { name: '분류' })).toBeNull()
  })
})
