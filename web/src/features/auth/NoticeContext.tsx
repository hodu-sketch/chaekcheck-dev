import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import styles from './NoticeBar.module.css'

/** The one-line notice under the header (S1): shown once, closable (flow.md). */
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

export function NoticeBar() {
  const { notice, clearNotice } = useNotice()
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
