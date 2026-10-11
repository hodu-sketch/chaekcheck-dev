import type { PopularBook } from '../../api/types'
import { REQUEST_LINES } from './popularText'
import { PopularWindow } from './PopularWindow'
import styles from './PopularDialogs.module.css'

type Props = {
  /** The faded book that was tapped; null keeps the window closed. */
  book: PopularBook | null
  onClose: () => void
  phone: boolean
}

/** S15 (R6-9, AC-10): a faded book (no e-book in the searched libraries): its title, then where to ask for it. */
export function RequestNotice({ book, onClose, phone }: Props) {
  return (
    <PopularWindow
      open={book !== null}
      onOpenChange={(open) => !open && onClose()}
      title="전자책 신청 안내"
      phone={phone}
      size="small"
    >
      {book && (
        <div className={styles.notice}>
          <span className={styles.noticeIcon} aria-hidden="true">
            <svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
              <path d="M4 19.5V5a2 2 0 0 1 2-2h13v15H6.5A2.5 2.5 0 0 0 4 20.5 2.5 2.5 0 0 0 6.5 23H19v-5" />
              <path d="M12 7v5M12 15h.01" />
            </svg>
          </span>
          <p className={styles.noticeTitle}>{book.title}</p>
          <p className={styles.noticeText}>
            {REQUEST_LINES[0]}
            <br />
            {REQUEST_LINES[1]}
          </p>
        </div>
      )}
    </PopularWindow>
  )
}
