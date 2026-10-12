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
  // No login button and no "사서추천도서" on the terms and privacy pages (flow.md S8, S9; cycle 10, 14 [확정])
  const legal = ['/terms', '/privacy'].includes(location.pathname)
  return (
    <div className={styles.shell}>
      {/* data-app-header: screens with their own sticky bar read its height (cycle 10 S13) */}
      <div className={styles.headerBar} data-app-header="">
        <header className={styles.header}>
          {/* no tagline next to the logo on any width (cycle 10, user 2026-10-11) */}
          <div className={styles.start}>
            <Link to="/" className={styles.brand}>
              <span className={styles.wordmark}>책첵</span>
            </Link>
            {!legal && (
              <Link to="/picks" className={styles.navLink} aria-current={location.pathname === '/picks' ? 'page' : undefined}>
                사서추천도서
              </Link>
            )}
          </div>
          {!legal && <AuthHeaderAction />}
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
