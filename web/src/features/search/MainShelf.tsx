import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { fetchPicksShelf } from '../../api/client'
import type { PickBook } from '../../api/types'
import { CoverImage } from '../picks/CoverImage'
import { PicksBookDialog } from '../picks/PicksBookDialog'
import { picksList } from '../picks/picksLists'
import { usePicksPlan } from '../picks/usePicksPlan'
import styles from './MainShelf.module.css'

/** [확정] (user 2026-10-22, design A). */
export const SHELF_TEXT = {
  title: '이런 책도 무료로 빌려 보세요',
  more: '추천도서 더 보기 →',
  // phones: shorter, so the heading stays on one line
  morePhone: '더 보기 →',
} as const

/**
 * The first screen's shelf in place of the 「만약에 …에 살면」 card (user 2026-10-22: "이렇게 하되 책을 분야별로 인기 있는
 * 거 한 권씩"): one KAIST book per field, the server's draw from each field's ten most held books of the newest year. A
 * cover opens the recommended books' book window as on the picks screen; nothing shows while it loads or when it fails.
 */
export function MainShelf({ phone }: { phone: boolean }) {
  const { plan, ready, key } = usePicksPlan()
  const shelf = useQuery({
    queryKey: ['picksShelf', key],
    queryFn: ({ signal }) => fetchPicksShelf(plan, signal),
    enabled: ready,
    // one draw per visit of the page, not again on every focus
    staleTime: Infinity,
    retry: false,
  })
  const [selected, setSelected] = useState<PickBook | null>(null)
  const navigate = useNavigate()
  const books = shelf.data?.books ?? []
  if (books.length === 0) return null
  const list = picksList(shelf.data!.list)

  return (
    <section className={styles.shelf} aria-label={SHELF_TEXT.title}>
      <div className={styles.head}>
        <h2 className={styles.title}>{SHELF_TEXT.title}</h2>
        <Link to={`/picks/${shelf.data!.list}`} className={styles.more}>
          {phone ? SHELF_TEXT.morePhone : SHELF_TEXT.more}
        </Link>
      </div>
      <ul className={styles.books}>
        {books.map((book) => (
          <li key={book.id}>
            <button type="button" className={styles.book} aria-label={book.title} onClick={() => setSelected(book)}>
              <CoverImage className={styles.cover} url={book.coverUrl} title={book.title} />
              <span className={styles.bookTitle}>{book.title}</span>
            </button>
          </li>
        ))}
      </ul>
      <PicksBookDialog
        book={selected}
        plan={plan}
        onClose={() => setSelected(null)}
        onSearch={(bookId) => navigate(`/books/${bookId}`)}
        phone={phone}
        yearLine={selected ? (list?.yearLine?.(selected) ?? null) : null}
      />
    </section>
  )
}
