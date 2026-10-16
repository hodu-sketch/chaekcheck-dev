import type { PickBook } from '../../api/types'
import { CoverImage } from './CoverImage'
import styles from './PicksPage.module.css'

type Props = {
  book: PickBook
  onPick: (book: PickBook) => void
  /** Under the cover (cycle 21 R5-1, R5-2): Lee Dong-jin "26.06", the SNU number; none for the librarian picks. */
  label?: string | null
}

/**
 * One picked book (R4-5): the cover only, no rank chip (cycle 14 [확정]); without an e-book in the searched libraries
 * the cover is faded, with no words (AC-6). A tap opens S14, or S15 for a faded one.
 */
export function CoverCard({ book, onPick, label: under = null }: Props) {
  // [안] name for screen readers (cycle 14 handoff step 6-4)
  const label = `${book.title}${under ? ` (${under})` : ''}, 전자책 ${book.available ? '있음' : '없음'}`
  return (
    <li className={styles.cell}>
      <button
        type="button"
        className={styles.card}
        data-available={book.available}
        aria-label={label}
        onClick={() => onPick(book)}
      >
        <CoverImage className={styles.cover} url={book.coverUrl} title={book.title} />
      </button>
      {under && (
        <span className={styles.coverLabel} aria-hidden="true">
          {under}
        </span>
      )}
    </li>
  )
}
