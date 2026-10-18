import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, fetchBook } from '../../api/client'
import { BookPage } from './BookPage'

vi.mock('../../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/client')>()),
  fetchBook: vi.fn(),
}))
vi.mock('../region/RegionContext', () => ({
  useRegions: () => ({ regions: { residence: [], work: [] }, libraryIds: [], ready: true }),
}))

afterEach(cleanup)

/** Cycle 08 (R7, ADR-0070, AC-8c): a book id that an import of the libraries' lists may have dropped. */
describe('book that is not found', () => {
  it('explains that the lists were renewed and leads back to the start', async () => {
    vi.mocked(fetchBook).mockRejectedValue(
      new ApiError(404, { title: 'Not Found', status: 404, code: 'book_not_found' }),
    )
    render(
      <MemoryRouter initialEntries={['/books/9999999']}>
        <Routes>
          <Route path="/books/:bookId" element={<BookPage />} />
        </Routes>
      </MemoryRouter>,
    )
    expect(await screen.findByText('책을 찾을 수 없어요')).toBeTruthy()
    expect(screen.getByText('도서관 목록을 새로 받으면서 빠진 책일 수 있어요. 다시 검색해 주세요.')).toBeTruthy()
    expect(screen.getByRole('link', { name: '처음으로' }).getAttribute('href')).toBe('/')
  })
})

/** Cycle 22 (R4-1, D9): no switch; the book screen's plan is joinable unless the address has scope=all. */
describe('scope', () => {
  const plan = () => vi.mocked(fetchBook).mock.calls.at(-1)![1]

  function renderAt(address: string) {
    vi.mocked(fetchBook).mockReset()
    vi.mocked(fetchBook).mockReturnValue(new Promise(() => {}))
    render(
      <MemoryRouter initialEntries={[address]}>
        <Routes>
          <Route path="/books/:bookId" element={<BookPage />} />
        </Routes>
      </MemoryRouter>,
    )
  }

  it('is joinable without a stored switch value, and with an old one', async () => {
    renderAt('/books/5001')
    await vi.waitFor(() => expect(plan().scope).toBe('joinable'))
    cleanup()

    localStorage.setItem('chaekcheck.searchSettings.v1', JSON.stringify({ guestScope: 'all', myScope: 'all' }))
    renderAt('/books/5001')
    await vi.waitFor(() => expect(plan().scope).toBe('joinable'))
    localStorage.clear()
  })

  it('is all with scope=all in the address', async () => {
    renderAt('/books/5001?scope=all')
    await vi.waitFor(() => expect(plan().scope).toBe('all'))
  })
})
