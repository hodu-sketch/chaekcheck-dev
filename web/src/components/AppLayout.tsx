import { Link, Outlet, useLocation } from 'react-router'
import { AuthHeaderAction } from '../features/auth/AuthHeaderAction'
import { NoticeBar } from '../features/auth/NoticeContext'
import { RegionImportDialog } from '../features/mylibrary/RegionImportDialog'
import { ErrorBoundary } from './ErrorBoundary'
import { ErrorFallback } from './ErrorFallback'
import { Footer } from './Footer'
import { HeaderMenu, PhoneMenu } from './HeaderMenu'
import { toggleMenusAt } from './headerMenus'
import styles from './AppLayout.module.css'

export function AppLayout() {
  const location = useLocation()
  return (
    <div className={styles.shell}>
      {/* data-app-header: screens with their own sticky bar read its height (cycle 10 S13) */}
      <div className={styles.headerBar} data-app-header="">
        <header className={styles.header}>
          {/* no tagline next to the logo on any width (cycle 10, user 2026-10-11) */}
          <div className={styles.start}>
            {/* the logo goes to the main page and opens or closes the menus there (user 2026-10-16) */}
            <Link to="/" className={styles.brand} data-menu-toggle="" onClick={() => toggleMenusAt('/')}>
              <span className={styles.wordmark}>책첵</span>
            </Link>
            <HeaderMenu />
          </div>
          {/* the same header on every screen, the terms and privacy pages too (cycle 21 spec R1-6, cycle 20) */}
          <div className={styles.end}>
            {/* cycle 20 puts the lookup status dot here: left of 로그인 (spec R1-6) */}
            <span className={styles.statusSlot} data-lookup-status-slot="" aria-hidden="true" />
            <AuthHeaderAction />
          </div>
        </header>
        {/* phones: the header row itself opens the menus (user 2026-10-16) */}
        <PhoneMenu />
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
