import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLocation } from 'react-router'
import styles from './NoticeBar.module.css'

/**
 * The one-line notice under the header (S1): shown once, closable (flow.md). It also goes away when the reader moves
 * to another page (user request 2026-10-05, after "가입했어요" stayed on every page).
 */
interface NoticeContextValue {
  notice: string | null
  showNotice: (message: string) => void
  clearNotice: () => void
}

const NoticeContext = createContext<NoticeContextValue | null>(null)

export function NoticeProvider({ children }: { children: ReactNode }) {
  const [notice, setNotice] = useState<string | null>(null)
  const showNotice = useCallback((message: string) => setNotice(message), [])
  const clearNotice = useCallback(() => setNotice(null), [])
  const value = useMemo(() => ({ notice, showNotice, clearNotice }), [notice, showNotice, clearNotice])
  return <NoticeContext.Provider value={value}>{children}</NoticeContext.Provider>
}

export function useNotice(): NoticeContextValue {
  const value = useContext(NoticeContext)
  if (value === null) throw new Error('useNotice needs <NoticeProvider>')
  return value
}

/** For screens also used without the provider: `null` there. */
export function useOptionalNotice(): NoticeContextValue | null {
  return useContext(NoticeContext)
}

/** Pages a notice only passes through on its way to the page it is meant for. */
const PASSING_THROUGH = new Set(['/auth/complete', '/signup'])

export function NoticeBar() {
  const { notice, clearNotice } = useNotice()
  const { pathname } = useLocation()
  // the page the current notice landed on
  const landed = useRef<{ notice: string; pathname: string } | null>(null)

  useEffect(() => {
    if (notice === null) {
      landed.current = null
      return
    }
    if (PASSING_THROUGH.has(pathname)) return
    if (landed.current === null || landed.current.notice !== notice) {
      landed.current = { notice, pathname }
    } else if (landed.current.pathname !== pathname) {
      landed.current = null
      clearNotice()
    }
  }, [notice, pathname, clearNotice])

  if (notice === null) return null
  return (
    <div className={styles.bar} role="status">
      <p className={styles.text}>{notice}</p>
      <button type="button" className={styles.close} onClick={clearNotice} aria-label="안내 닫기">
        닫기
      </button>
    </div>
  )
}
