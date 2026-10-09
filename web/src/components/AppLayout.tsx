import { Link, Outlet, useLocation } from 'react-router'
import { AuthHeaderAction } from '../features/auth/AuthHeaderAction'
import { NoticeBar } from '../features/auth/NoticeContext'
import { RegionImportDialog } from '../features/mylibrary/RegionImportDialog'
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
            {/* the 책 mark (user request 2026-10-09); decorative, so the link reads "책첵" once */}
            <img className={styles.mark} src="/brand/chaekcheck-mark.svg" alt="" width={32} height={32} />
            <span className={styles.wordmark}>책첵</span>
            <span className={styles.tagline}>전자도서관 대출 가능 확인</span>
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
      <RegionImportDialog />
    </div>
  )
}
