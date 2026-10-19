import type { PickBook } from '../../api/types'
import { CoverImage } from './CoverImage'
import styles from './PicksPage.module.css'

type Props = {
  book: PickBook
  /** The grey line under the title (the recommendation line, or the field). */
  sub: string
  onPick: (book: PickBook) => void
}

/**
 * Whether the book is linked to an e-book in the searched libraries: our book (`bookId`) held by a plan library
 * (`available`). The same test decides which window a tap opens (S14 or S15).
 */
export function isLinked(book: PickBook): boolean {
  return book.available && book.bookId != null
}

/**
 * One picked book (user 2026-10-19 redesign): a white card with the cover centred at the top (or the title in its
 * place), the title in up to two lines, a grey line, and a pill: "전자책 연결 있음" or "현재 범위에서 미연결".
 * A tap opens S14, or S15 for a book that is not linked.
 */
export function BookCard({ book, sub, onPick }: Props) {
  const linked = isLinked(book)
  // [안] name for screen readers (cycle 14 handoff step 6-4)
  const label = `${book.title}, 전자책 ${linked ? '있음' : '없음'}`
  return (
    <li className={styles.cell}>
      <button type="button" className={styles.card} data-linked={linked} aria-label={label} onClick={() => onPick(book)}>
        <span className={styles.coverBox}>
          <CoverImage className={styles.cover} url={book.coverUrl} title={book.title} />
        </span>
        <span className={styles.cardTitle}>{book.title}</span>
        {sub && <span className={styles.cardSub}>{sub}</span>}
        <span className={styles.pillRow}>
          <span className={styles.pill} data-linked={linked}>
            {linked ? '전자책 연결 있음' : '현재 범위에서 미연결'}
          </span>
        </span>
      </button>
    </li>
  )
}
