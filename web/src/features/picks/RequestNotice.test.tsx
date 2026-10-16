import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RequestNotice } from './RequestNotice'
import { testBooks } from './testPicks'
import { PICKS_LISTS } from './picksLists'

afterEach(cleanup)

/** S15 (R6-9, AC-10). */
describe('request notice', () => {
  it('shows the title on one line, then the notice in two lines', () => {
    render(<RequestNotice book={testBooks('문학', '11')[1]} onClose={vi.fn()} phone={false} list={PICKS_LISTS.librarian} />)
    const dialog = screen.getByRole('dialog', { name: '전자책 신청 안내' })
    const lines = Array.from(dialog.querySelectorAll('p')).map((p) => p.textContent)
    expect(lines).toEqual(['문학 2', '아직 검색하는 도서관에 전자책이 없어요.이용하는 전자도서관 사이트에서 희망도서로 신청해 보세요.'])
    expect(dialog.querySelectorAll('p')[1].querySelector('br')).not.toBeNull()
  })

  it('closes with Escape on PC', async () => {
    const onClose = vi.fn()
    render(<RequestNotice book={testBooks('문학', '11')[1]} onClose={onClose} phone={false} list={PICKS_LISTS.librarian} />)
    await userEvent.setup().keyboard('{Escape}')
    expect(onClose).toHaveBeenCalled()
  })

  it('is closed without a book', () => {
    render(<RequestNotice book={null} onClose={vi.fn()} phone={false} list={PICKS_LISTS.librarian} />)
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
