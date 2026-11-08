import styles from './ResultList.module.css'

/**
 * Texts of the first holding stream refused with 503 `server_busy` (cycle 28, ADR-0173) [확정] (user approval,
 * decision page 2026-11-07). `BLOCKED_TEXT.busy` (`blocked.ts`) stays for the other refusals.
 */
/** 바로 보기: above the book lines kept in place of the results. */
export const BUSY_KEEP_LIST_TEXT = '지금 찾는 사람이 많아 대출 정보는 알 수 없어요. 책 목록만 먼저 보여 드려요.'
/** The book screen: the first sentence only. */
export const BUSY_BOOK_TEXT = '지금 찾는 사람이 많아 대출 정보는 알 수 없어요.'
/** While the stream is opened again after the button. */
export const BUSY_CHECKING_TEXT = '대출 정보 확인 중…'
/** The button [확정]. */
export const BUSY_RETRY_LABEL = '대출 정보 다시 확인'

type Props = {
  /** The line's words while nothing is being asked (`BUSY_KEEP_LIST_TEXT`, `BUSY_BOOK_TEXT` or `BLOCKED_TEXT.busy`). */
  text: string
  /** The stream is being opened again: the line says so and the button is locked. */
  checking: boolean
  /** Opens the same first stream again (`useHoldings().reopen`). Only a tap asks again: no retry by itself [확정]. */
  onRetry: () => void
}

/**
 * The `.blocked` line (cycle 08 R2 shape) with the 「대출 정보 다시 확인」 button at its end; on phones the button goes
 * under the words (decision page 2026-11-07).
 */
export function BusyRetryNotice({ text, checking, onRetry }: Props) {
  return (
    <div className={`${styles.blocked} ${styles.busyRetry}`} role="alert">
      <span>{checking ? BUSY_CHECKING_TEXT : text}</span>
      <button type="button" className={styles.retryButton} onClick={onRetry} disabled={checking}>
        {BUSY_RETRY_LABEL}
      </button>
    </div>
  )
}
