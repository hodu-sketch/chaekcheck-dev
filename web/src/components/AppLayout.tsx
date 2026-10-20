import { useEffect } from 'react'
import { Link, Outlet, useLocation, useNavigationType } from 'react-router'
import { AuthHeaderAction } from '../features/auth/AuthHeaderAction'
import { NoticeBar } from '../features/auth/NoticeContext'
import { RegionImportDialog } from '../features/mylibrary/RegionImportDialog'
import { ErrorBoundary } from './ErrorBoundary'
import { ErrorFallback } from './ErrorFallback'
import { Footer } from './Footer'
import { HeaderMenu, PhoneMenu } from './HeaderMenu'
import { ABOUT_LINK } from './headerMenus'
import styles from './AppLayout.module.css'

export function AppLayout() {
  const location = useLocation()
  const navigationType = useNavigationType()
  // a new page starts at the top (user 2026-10-19: "스크롤 내린 채로 다른 탭 이동하면 … 안 그러게"); back/forward (POP)
  // keeps the browser's own restore, and a page that restores its own place (picks) still does after this
  useEffect(() => {
    if (navigationType !== 'POP') window.scrollTo(0, 0)
  }, [location.pathname, navigationType])
  return (
    <div className={styles.shell}>
      {/* data-app-header: screens with their own sticky bar read its height (cycle 10 S13) */}
      <div className={styles.headerBar} data-app-header="">
        <header className={styles.header}>
          {/* no tagline next to the logo on any width (cycle 10, user 2026-10-11) */}
          <div className={styles.start}>
            {/* phones: ☰ at the far left, before the logo (user 2026-10-19); hidden on wider screens */}
            <PhoneMenu />
            {/* the logo only goes to the main page, menus or not (user 2026-10-16, after using it: "좌상단 로고는 아래 탭
                늘려서 보이는 거랑 관계 없이 그냥 메인으로 옮겨주는 걸로만 해") */}
            <span className={styles.brandGroup}>
              <Link to="/" className={styles.brand}>
                <span className={styles.wordmark}>책첵</span>
              </Link>
              {/* 소개 right next to the logo, quieter than the menus (user 2026-10-20: "좌상단 책첵 바로 오른쪽이어야지";
                  after the menus for a while the same day) */}
              <Link
                to={ABOUT_LINK.to}
                className={styles.about}
                data-current={location.pathname === ABOUT_LINK.to ? '' : undefined}
                aria-current={location.pathname === ABOUT_LINK.to ? 'page' : undefined}
              >
                {ABOUT_LINK.label}
              </Link>
            </span>
            <HeaderMenu />
          </div>
          {/* the same header on every screen, the terms and privacy pages too (cycle 21 spec R1-6, cycle 20) */}
          <div className={styles.end}>
            <AuthHeaderAction />
          </div>
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
