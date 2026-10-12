import type { PickBook } from '../../api/types'
import { CoverImage } from './CoverImage'
import styles from './PicksPage.module.css'

type Props = {
  book: PickBook
  onPick: (book: PickBook) => void
}

/**
 * One picked book (R4-5): the cover only, no rank chip (cycle 14 [확정]); without an e-book in the searched libraries
 * the cover is faded, with no words (AC-6). A tap opens S14, or S15 for a faded one.
 */
export function CoverCard({ book, onPick }: Props) {
  // [안] name for screen readers (cycle 14 handoff step 6-4)
  const label = `${book.title}, 전자책 ${book.available ? '있음' : '없음'}`
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
    </li>
  )
}
