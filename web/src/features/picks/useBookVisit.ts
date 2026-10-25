import { useEffect } from 'react'
import { recordDwelled, recordOpened } from './viewedBooks'

/** [확정] R5-2, R5-4: kept visible this long counts as "열어 둔 적 있음". */
export const DWELL_MS = 10_000

/**
 * Counts one look at a book for 「나에게 맞는 책」 (R5-3, R5-4): opened when the book screen or a picks book window
 * shows the book, and kept open after 10 seconds while the page is visible (time on another tab does not count).
 * Nothing is sent to the server (R5-7).
 */
export function useBookVisit(bookId: number | null | undefined, title: string | null | undefined): void {
  useEffect(() => {
    if (bookId == null || !title) return
    recordOpened(bookId, title)
    let spent = 0
    let startedAt: number | null = null
    let timer: number | undefined
    const stop = () => {
      if (startedAt !== null) spent += Date.now() - startedAt
      startedAt = null
      window.clearTimeout(timer)
    }
    const start = () => {
      if (startedAt !== null || document.visibilityState === 'hidden') return
      startedAt = Date.now()
      timer = window.setTimeout(() => {
        stop()
        recordDwelled(bookId, title)
        document.removeEventListener('visibilitychange', onVisibility)
      }, DWELL_MS - spent)
    }
    const onVisibility = () => (document.visibilityState === 'hidden' ? stop() : start())
    document.addEventListener('visibilitychange', onVisibility)
    start()
    return () => {
      stop()
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [bookId, title])
}
