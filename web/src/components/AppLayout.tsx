import { Link, Outlet, useLocation } from 'react-router'
import { AuthHeaderAction } from '../features/auth/AuthHeaderAction'
import { NoticeBar } from '../features/auth/NoticeContext'
import { ErrorBoundary } from './ErrorBoundary'
import { ErrorFallback } from './ErrorFallback'
import { Footer } from './Footer'
import styles from './AppLayout.module.css'

export function AppLayout() {
  const location = useLocation()
  return (
    <div className={styles.shell}>
      <div className={styles.headerBar}>
        <header className={styles.header}>
          <Link to="/" className={styles.brand}>
            <span className={styles.wordmark}>책첵</span>
            <span className={styles.tagline}>전자도서관 대출 가능 여부를 한 번에</span>
          </Link>
          {/* No login button on the terms and privacy pages (flow.md S8, S9) */}
          {!['/terms', '/privacy'].includes(location.pathname) && <AuthHeaderAction />}
        </header>
      </div>
      <main className={styles.main}>
        <NoticeBar />
        {/* after an error, a new address (new search, back) renders the screen again */}
        <ErrorBoundary
          resetKey={location.pathname + location.search}
          fallback={(reset) => <ErrorFallback onRetry={reset} />}
        >
          <Outlet />
        </ErrorBoundary>
      </main>
      <Footer />
    </div>
  )
}
