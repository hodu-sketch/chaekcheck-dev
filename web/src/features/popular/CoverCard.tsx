import type { PopularBook } from '../../api/types'
import { CoverImage } from './CoverImage'
import styles from './PopularPage.module.css'

type Props = {
  book: PopularBook
  onPick: (book: PopularBook) => void
}

/**
 * One ranked book (R4-5): the cover only, a small rank at the top left; without an e-book in my libraries the cover
 * is faded, with no words (AC-6). A tap opens S14, or S15 for a faded one.
 */
export function CoverCard({ book, onPick }: Props) {
  // [안] name for screen readers (handoff step 7-4)
  const label = `${book.rank}위 ${book.title}, 전자책 ${book.available ? '있음' : '없음'}`
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
        <span className={styles.rank} aria-hidden="true">
          {book.rank}
        </span>
      </button>
    </li>
  )
}
