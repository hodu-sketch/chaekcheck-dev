import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { EMPTY_INPUT } from './searchCondition'
import { SearchForm } from './SearchForm'
import { DEFAULT_SETTINGS, DIRECT_REASON } from './searchSettings'

afterEach(cleanup)

beforeAll(() => {
  // Radix positions popovers with ResizeObserver, which jsdom does not have
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

function setup(props: Partial<Parameters<typeof SearchForm>[0]> = {}) {
  const onSearch = vi.fn()
  const onSettingsChange = vi.fn()
  render(
    <SearchForm
      initial={EMPTY_INPUT}
      settings={DEFAULT_SETTINGS}
      hasResults={false}
      onSearch={onSearch}
      onSettingsChange={onSettingsChange}
      {...props}
    />,
  )
  return { onSearch, onSettingsChange, user: userEvent.setup() }
}

const titleBox = () => screen.getByRole('searchbox', { name: '책 제목' })
const moreButton = () => screen.getByRole('button', { name: '여러 조건' })
const box = (name: string) => screen.getByRole('searchbox', { name }) as HTMLInputElement
const check = (name: string | RegExp) => screen.getByRole('checkbox', { name }) as HTMLInputElement

describe('SearchForm (cycle 04 AC-1)', () => {
  it('shows one title box, "여러 조건" and settings, and no condition dropdown', () => {
    setup()

    expect(titleBox().getAttribute('placeholder')).toBe('책 제목을 입력하세요')
    expect(moreButton().getAttribute('aria-expanded')).toBe('false')
    expect(screen.getByRole('button', { name: '검색 설정' })).toBeTruthy()
    expect(screen.queryByRole('combobox')).toBeNull()
    expect(screen.queryByRole('searchbox', { name: '저자' })).toBeNull()
  })

  it('"여러 조건" shows author and publisher; opening does not search [안]', async () => {
    const { user, onSearch } = setup()

    await user.click(moreButton())

    expect(box('저자')).toBeTruthy()
    expect(box('출판사')).toBeTruthy()
    expect(check('옮긴이').checked).toBe(false)
    expect(check('그림').checked).toBe(false)
    expect(check('엮은이').checked).toBe(false)
    expect(onSearch).not.toHaveBeenCalled()
  })

  it('searches every filled field, normalized; empty fields are left out', async () => {
    const { user, onSearch } = setup()

    await user.type(titleBox(), '  행동  ')
    await user.click(moreButton())
    await user.type(box('저자'), '새폴스키')
    await user.click(check(/완전 일치.*저자/))
    await user.click(check('옮긴이'))
    await user.click(screen.getByRole('button', { name: '검색' }))

    expect(onSearch).toHaveBeenCalledWith({
      title: '행동',
      author: '새폴스키',
      publisher: '',
      exact: ['author'],
      roles: ['translator'],
      sort: 'default',
    })
  })

  it('an author alone can be searched', async () => {
    const { user, onSearch } = setup()

    await user.click(moreButton())
    await user.type(box('저자'), '김달')
    await user.click(screen.getByRole('button', { name: '검색' }))

    expect(onSearch).toHaveBeenCalledWith(expect.objectContaining({ title: '', author: '김달' }))
  })

  it('no filled field: "검색어를 입력하세요" and nothing is sent', async () => {
    const { user, onSearch } = setup()

    await user.click(screen.getByRole('button', { name: '검색' }))

    expect(screen.getByRole('alert').textContent).toContain('검색어를 입력하세요')
    expect(onSearch).not.toHaveBeenCalled()
  })

  it('a filled field shorter than 2 characters gets the cycle 01 message under it', async () => {
    const { user, onSearch } = setup()

    await user.type(titleBox(), '행동')
    await user.click(moreButton())
    await user.type(box('출판사'), '문')
    await user.click(screen.getByRole('button', { name: '검색' }))

    expect(screen.getByRole('alert').textContent).toContain('2글자 이상 입력하세요')
    expect(box('출판사').getAttribute('aria-invalid')).toBe('true')
    expect(onSearch).not.toHaveBeenCalled()
  })

  it('opens with the address conditions; with results a changed check searches again [안]', async () => {
    const initial = { ...EMPTY_INPUT, title: '행동', author: '새폴스키' }
    const { user, onSearch } = setup({ initial, hasResults: true })

    expect(moreButton().getAttribute('aria-expanded')).toBe('true')
    await user.click(check(/완전 일치.*제목/))

    expect(onSearch).toHaveBeenCalledWith(expect.objectContaining({ title: '행동', author: '새폴스키', exact: ['title'] }))
  })

  it('direct search dims what the site search cannot use, with the reason', async () => {
    const { user, onSearch } = setup({ settings: { view: 'instant', direct: true } })

    await user.click(moreButton())

    expect(screen.getByText(DIRECT_REASON)).toBeTruthy()
    expect(box('저자').disabled).toBe(true)
    expect(check(/완전 일치.*제목/).disabled).toBe(true)
    expect(check('옮긴이').disabled).toBe(true)
    await user.type(titleBox(), '사랑')
    await user.click(screen.getByRole('button', { name: '검색' }))
    expect(onSearch).toHaveBeenCalledWith(expect.objectContaining({ title: '사랑', author: '' }))
  })

  it('settings change at once', async () => {
    const { user, onSettingsChange } = setup()

    await user.click(screen.getByRole('button', { name: '검색 설정' }))
    await user.click(screen.getByRole('radio', { name: /책 먼저 고르기/ }))
    expect(onSettingsChange).toHaveBeenLastCalledWith({ view: 'books', direct: false })

    await user.click(screen.getByRole('radio', { name: /^켬/ }))
    expect(onSettingsChange).toHaveBeenLastCalledWith({ view: 'instant', direct: true })
  })

  it('with direct search on, 책 먼저 고르기 cannot be chosen [안]', async () => {
    const { user } = setup({ settings: { view: 'instant', direct: true } })

    await user.click(screen.getByRole('button', { name: '검색 설정' }))

    expect((screen.getByRole('radio', { name: /책 먼저 고르기/ }) as HTMLButtonElement).disabled).toBe(true)
  })
})
