import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RequestNotice } from './RequestNotice'
import { testBooks } from './testPopular'

afterEach(cleanup)

/** S15 (R6-9, AC-10). */
describe('request notice', () => {
  it('shows the title on one line, then the notice', () => {
    render(<RequestNotice book={testBooks('소설')[1]} onClose={vi.fn()} phone={false} />)
    const dialog = screen.getByRole('dialog', { name: '전자책 신청 안내' })
    const lines = Array.from(dialog.querySelectorAll('p')).map((p) => p.textContent)
    expect(lines).toEqual([
      '소설 2',
      '아직 내 도서관에 전자책이 없어요. 이용하는 전자도서관 사이트에서 희망도서로 신청해 보세요.',
    ])
  })

  it('closes with Escape on PC', async () => {
    const onClose = vi.fn()
    render(<RequestNotice book={testBooks('소설')[1]} onClose={onClose} phone={false} />)
    await userEvent.setup().keyboard('{Escape}')
    expect(onClose).toHaveBeenCalled()
  })

  it('is closed without a book', () => {
    render(<RequestNotice book={null} onClose={vi.fn()} phone={false} />)
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
