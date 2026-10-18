import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, beforeAll, describe, expect, it } from 'vitest'
import { CategoryPicker } from './CategoryPicker'
import { CATEGORIES, testCategories } from './testPicks'
import { PICKS_LISTS } from './picksLists'

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
      fetchedAt="2026-10-12T05:00:41+09:00"
      list={PICKS_LISTS.librarian}
    />
  )
}

/** S13c (R5-1, R5-2, AC-5). */
describe('category list', () => {
  it('lists the fields in screen order, a new one last, the current one marked', async () => {
    render(<Harness />)
    await userEvent.setup().click(screen.getByRole('button', { name: '분류: 전체' }))
    const items = within(screen.getByRole('list', { name: '분류' })).getAllByRole('button')
    expect(items.map((b) => b.textContent)).toEqual(CATEGORIES.map((c) => c.name))
    expect(items[0].getAttribute('aria-current')).toBe('true')
    expect(items.filter((b) => b.hasAttribute('aria-current'))).toHaveLength(1)
  })

  it('picking 인문 closes the list and changes the box', async () => {
    render(<Harness />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '분류: 전체' }))
    await user.click(screen.getByRole('button', { name: '인문' }))
    expect(screen.queryByRole('list', { name: '분류' })).toBeNull()
    expect(screen.getByRole('button', { name: '분류: 인문' }).textContent).toContain('인문')
  })

  it('"추천 기준 보기" opens the explanation in the list without closing it (S13i)', async () => {
    render(<Harness />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '분류: 전체' }))
    const help = screen.getByRole('button', { name: '추천 기준 보기' })
    expect(help.getAttribute('aria-expanded')).toBe('false')
    await user.click(help)
    expect(help.getAttribute('aria-expanded')).toBe('true')
    expect(screen.getByRole('list', { name: '분류' })).toBeTruthy()
    expect(screen.getByText('국립중앙도서관 사서가 고른 신간 추천 도서 중 최근 12번(2년) 추천분이에요')).toBeTruthy()
    expect(screen.getByText('보통 2달에 한 번, 한 번에 8권(문학·인문·사회·자연 각 2권)을 추천해요. 테마 추천 때는 12권이에요')).toBeTruthy()
    expect(screen.getByText('신간이라 아직 전자책이 없는 책도 있어요')).toBeTruthy()
    expect(screen.getByText('흐린 책은 검색하는 도서관에 전자책이 없어요')).toBeTruthy()
    expect(screen.getByText('있음/없음은 검색 화면에서 고른 지역 기준이에요')).toBeTruthy()
    expect(screen.getByText('출처: 국립중앙도서관 사서추천도서 (공공누리 제1유형) · 10월 12일 받음')).toBeTruthy()
    expect(screen.queryByText(/종이책 순위|판매 순위/)).toBeNull()
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
