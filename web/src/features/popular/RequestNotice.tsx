import type { PopularBook } from '../../api/types'
import { REQUEST_TEXT } from './popularText'
import { PopularWindow } from './PopularWindow'
import styles from './PopularDialogs.module.css'

type Props = {
  /** The faded book that was tapped; null keeps the window closed. */
  book: PopularBook | null
  onClose: () => void
  phone: boolean
}

/** S15 (R6-9, AC-10): a faded book (no e-book in my libraries): its title on one line, then where to ask for it. */
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
          <p className={styles.noticeTitle}>{book.title}</p>
          <p className={styles.noticeText}>{REQUEST_TEXT}</p>
        </div>
      )}
    </PopularWindow>
  )
}
