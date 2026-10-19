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

/** A results screen of the given conditions. */
function results(conditions: Partial<typeof EMPTY_INPUT>, props: Partial<Parameters<typeof SearchForm>[0]> = {}) {
  return setup({ initial: { ...EMPTY_INPUT, ...conditions }, hasResults: true, ...props })
}

const titleBox = () => screen.getByRole('searchbox', { name: '책 제목' })
const button = (name: string | RegExp) => screen.getByRole('button', { name }) as HTMLButtonElement
const searchButton = () => button('검색')
const box = (name: string) => screen.getByRole('searchbox', { name }) as HTMLInputElement

describe('SearchForm: the first screen (상세검색 안 C, user 2026-10-19)', () => {
  it('shows one title box, [검색] and settings only: no 상세검색, no chips, no condition dropdown', () => {
    setup()

    expect(titleBox().getAttribute('placeholder')).toBe('책 제목을 입력하세요')
    expect(searchButton()).toBeTruthy()
    expect(button('검색 설정')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '상세검색' })).toBeNull()
    expect(screen.queryByRole('group', { name: '검색 좁히기' })).toBeNull()
    expect(screen.queryByRole('combobox')).toBeNull()
    expect(screen.queryByRole('searchbox', { name: '저자' })).toBeNull()
  })

  it('searches the title, normalized', async () => {
    const { user, onSearch } = setup()

    await user.type(titleBox(), '  행동  ')
    await user.click(searchButton())

    expect(onSearch).toHaveBeenCalledWith({ ...EMPTY_INPUT, title: '행동' })
  })

  it('no filled field: "검색어를 입력하세요" and nothing is sent', async () => {
    const { user, onSearch } = setup()

    await user.click(searchButton())

    expect(screen.getByRole('alert').textContent).toContain('검색어를 입력하세요')
    expect(onSearch).not.toHaveBeenCalled()
  })

  it('a title shorter than 2 characters gets the cycle 01 message', async () => {
    const { user, onSearch } = setup()

    await user.type(titleBox(), '행')
    await user.click(searchButton())

    expect(screen.getByRole('alert').textContent).toContain('2글자 이상 입력하세요')
    expect(titleBox().getAttribute('aria-invalid')).toBe('true')
    expect(onSearch).not.toHaveBeenCalled()
  })

  it('a too short author from the address is named under the box', () => {
    setup({ initial: { ...EMPTY_INPUT, title: '행동', author: '김' } })

    expect(screen.getByRole('alert').textContent).toBe('저자: 2글자 이상 입력하세요')
  })
})

describe('SearchForm: the narrowing chips on the results', () => {
  it('shows 저자, 출판사 and 제목 정확히; the roles only once an author is set', () => {
    results({ title: '소년이 온다' })

    expect(button('저자')).toBeTruthy()
    expect(button('출판사')).toBeTruthy()
    expect(button('제목 정확히').getAttribute('aria-pressed')).toBe('false')
    expect(screen.queryByRole('button', { name: '옮긴이 포함' })).toBeNull()
  })

  it('저자 opens a small box; [적용] searches again at once with the author', async () => {
    const { user, onSearch } = results({ title: '행동' })

    await user.click(button('저자'))
    expect(onSearch).not.toHaveBeenCalled()
    await user.type(box('저자'), ' 새폴스키 ')
    await user.click(screen.getByRole('checkbox', { name: '완전 일치' }))
    await user.click(button('적용'))

    expect(onSearch).toHaveBeenCalledWith({ ...EMPTY_INPUT, title: '행동', author: '새폴스키', exact: ['author'] })
    expect(screen.queryByRole('searchbox', { name: '저자' })).toBeNull()
  })

  it('Enter in the small box applies it too, and does not submit the title search twice', async () => {
    const { user, onSearch } = results({ title: '행동' })

    await user.click(button('출판사'))
    await user.type(box('출판사'), '문학동네{Enter}')

    expect(onSearch).toHaveBeenCalledTimes(1)
    expect(onSearch).toHaveBeenCalledWith(expect.objectContaining({ title: '행동', publisher: '문학동네' }))
  })

  it('a too short value stays in the small box with the message, nothing is sent', async () => {
    const { user, onSearch } = results({ title: '행동' })

    await user.click(button('출판사'))
    await user.type(box('출판사'), '문')
    await user.click(button('적용'))

    expect(screen.getByRole('alert').textContent).toContain('2글자 이상 입력하세요')
    expect(box('출판사').getAttribute('aria-invalid')).toBe('true')
    expect(onSearch).not.toHaveBeenCalled()
  })

  it('a set value is a filled chip "저자: 한강"; × clears it, with its roles, and searches again', async () => {
    const { user, onSearch } = results({ title: '소년이 온다', author: '한강', exact: ['author'], roles: ['translator'] })

    expect(button(/^저자: 한강/)).toBeTruthy()
    await user.click(button('저자 지우기'))

    expect(onSearch).toHaveBeenCalledWith({ ...EMPTY_INPUT, title: '소년이 온다' })
  })

  it('the only field cannot be cleared: "검색어를 입력하세요" and nothing is sent', async () => {
    const { user, onSearch } = results({ author: '김달' })

    await user.click(button('저자 지우기'))

    expect(screen.getByRole('alert').textContent).toContain('검색어를 입력하세요')
    expect(onSearch).not.toHaveBeenCalled()
  })

  it('an author alone can be searched; 제목 정확히 is not shown without a title', () => {
    results({ author: '김달' })

    expect(button(/^저자: 김달/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: '제목 정확히' })).toBeNull()
  })

  it('제목 정확히 is a toggle that searches again at once', async () => {
    const { user, onSearch } = results({ title: '행동', author: '새폴스키' })

    await user.click(button('제목 정확히'))

    expect(onSearch).toHaveBeenCalledWith(expect.objectContaining({ title: '행동', author: '새폴스키', exact: ['title'] }))
  })

  it('a role chip is a toggle that searches again at once; on, it is pressed', async () => {
    const { user, onSearch } = results({ title: '행동', author: '새폴스키', roles: ['illustrator'] })

    expect(button('그림 포함').getAttribute('aria-pressed')).toBe('true')
    await user.click(button('옮긴이 포함'))

    expect(onSearch).toHaveBeenLastCalledWith(
      expect.objectContaining({ author: '새폴스키', roles: ['illustrator', 'translator'] }),
    )
    await user.click(button('엮은이 포함'))
    expect(onSearch).toHaveBeenLastCalledWith(expect.objectContaining({ roles: ['illustrator', 'translator', 'editor'] }))
  })

  it('[검색] keeps the narrowing shown in the chips', async () => {
    const { user, onSearch } = results({ title: '행동', publisher: '문학동네' })

    await user.clear(titleBox())
    await user.type(titleBox(), '사랑')
    await user.click(searchButton())

    expect(onSearch).toHaveBeenCalledWith(expect.objectContaining({ title: '사랑', publisher: '문학동네' }))
  })

  it('direct search dims the chips with the reason and searches the title only', async () => {
    const { user, onSearch } = results({ title: '사랑', author: '김달' }, { settings: { view: 'instant', direct: true } })

    expect(screen.getByText(DIRECT_REASON)).toBeTruthy()
    expect(button(/^저자: 김달/).disabled).toBe(true)
    expect(button('출판사').disabled).toBe(true)
    expect(button('제목 정확히').disabled).toBe(true)
    expect(button('옮긴이 포함').disabled).toBe(true)
    await user.click(searchButton())
    expect(onSearch).toHaveBeenCalledWith(expect.objectContaining({ title: '사랑', author: '' }))
  })
})

describe('SearchForm: settings', () => {
  it('settings change at once', async () => {
    const { user, onSettingsChange } = setup()

    await user.click(button('검색 설정'))
    await user.click(screen.getByRole('radio', { name: /책 먼저 고르기/ }))
    expect(onSettingsChange).toHaveBeenLastCalledWith({ view: 'books', direct: false })

    await user.click(screen.getByRole('radio', { name: /^켬/ }))
    expect(onSettingsChange).toHaveBeenLastCalledWith({ view: 'instant', direct: true })
  })

  it('with direct search on, 책 먼저 고르기 cannot be chosen [안]', async () => {
    const { user } = setup({ settings: { view: 'instant', direct: true } })

    await user.click(button('검색 설정'))

    expect((screen.getByRole('radio', { name: /책 먼저 고르기/ }) as HTMLButtonElement).disabled).toBe(true)
  })
})

/** Cycle 22 R2-1: on phones the "내 도서관" chip sits on its own line right under the title box. */
describe('the phone chip', () => {
  it('comes right after the title box, before the settings', () => {
    setup({ chip: <button type="button">내 도서관: 서울 강남구 · 68곳</button> })
    const chip = button('내 도서관: 서울 강남구 · 68곳')
    expect(titleBox().compareDocumentPosition(chip) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(chip.compareDocumentPosition(button('검색 설정')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('the ⚙ sheet on phones has no "찾을 도서관" (the chip has its own sheet, user 2026-10-19)', async () => {
    const { user } = setup({ settingsSheet: true, chip: <button type="button">지역 설정</button> })
    await user.click(button('검색 설정'))
    const sheet = screen.getByRole('dialog', { name: '검색 설정' })
    expect(screen.getByTestId('sheet-handle')).toBeTruthy()
    expect(sheet.textContent).not.toContain('찾을 도서관')
  })

  it('is not there without one (wider screens)', () => {
    setup()
    expect(screen.queryByRole('button', { name: /^내 도서관/ })).toBeNull()
  })
})
