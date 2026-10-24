import { useState } from 'react'
import { FOR_YOU_TEXT } from './forYouText'
import { PicksWindow } from './PicksWindow'
import { baseBooks, clearViewed, readViewed, recentBooks, removeFromBase } from './viewedBooks'
import styles from './ForYouPage.module.css'

type Props = {
  /** closing asks for the personal picks again (R6-4) */
  onClose: () => void
  phone: boolean
}

function readView() {
  const now = Date.now()
  const viewed = readViewed(now)
  return { recent: recentBooks(viewed, now), base: new Set(baseBooks(viewed, now).map((book) => book.bookId)) }
}

/**
 * S19 (R6-4, AC-15): the books seen lately by points, at most 10, the current three base books marked. [빼기] keeps a
 * book out of the base books for 30 days and the next one comes up; "최근 본 책 기록 모두 지우기" at the end. Only this
 * browser's storage changes; nothing is sent.
 */
export function BaseBooksDialog({ onClose, phone }: Props) {
  // shown only while open: the record is read when the window opens and after each change in it
  const [view, setView] = useState(readView)
  const { recent, base } = view
  return (
    <PicksWindow open onOpenChange={(next) => !next && onClose()} title={FOR_YOU_TEXT.dialogTitle} phone={phone} size="small">
      <div className={styles.dialog}>
        <h2 className={styles.dialogTitle}>{FOR_YOU_TEXT.dialogTitle}</h2>
        <ol className={styles.recentList}>
          {recent.map((book) => (
            <li key={book.bookId} className={styles.recentRow}>
              <span className={styles.recentTitle}>
                {base.has(book.bookId) && <span className={styles.baseMark}>{FOR_YOU_TEXT.baseMark}</span>}
                {book.title}
              </span>
              <button
                type="button"
                className={styles.removeButton}
                aria-label={`${book.title} ${FOR_YOU_TEXT.remove}`}
                onClick={() => {
                  removeFromBase(book.bookId)
                  setView(readView())
                }}
              >
                {FOR_YOU_TEXT.remove}
              </button>
            </li>
          ))}
        </ol>
        <button
          type="button"
          className={styles.clearButton}
          onClick={() => {
            clearViewed()
            onClose()
          }}
        >
          {FOR_YOU_TEXT.clearAll}
        </button>
      </div>
    </PicksWindow>
  )
}
