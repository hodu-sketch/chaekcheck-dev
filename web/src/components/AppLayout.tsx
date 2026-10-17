import { Link, Outlet, useLocation } from 'react-router'
import { AuthHeaderAction } from '../features/auth/AuthHeaderAction'
import { NoticeBar } from '../features/auth/NoticeContext'
import { HeaderSignal } from '../features/lookupstatus/HeaderSignal'
import { LookupStatusProvider } from '../features/lookupstatus/LookupStatusProvider'
import { RegionImportDialog } from '../features/mylibrary/RegionImportDialog'
import { ErrorBoundary } from './ErrorBoundary'
import { ErrorFallback } from './ErrorFallback'
import { Footer } from './Footer'
import { HeaderOpenProvider } from './HeaderOpen'
import { ChevronDown, ChevronUp } from './icons'
import { HeaderMenu, PhoneMenu } from './HeaderMenu'
import { useHeaderOpen } from './HeaderOpen'
import styles from './AppLayout.module.css'

export function AppLayout() {
  return (
    // cycle 20: the lookup status is asked here, once for every screen (handoff 1-12); the header's pop-ups (menus,
    // status popover) share one "who is open" (HeaderOpen)
    <LookupStatusProvider>
      <HeaderOpenProvider>
        <Shell />
      </HeaderOpenProvider>
    </LookupStatusProvider>
  )
}

function Shell() {
  const location = useLocation()
  // phones: the ▾ turns into ▴ while the menus are open (user 2026-10-17: "탭 누르고 아래 상세 탭이 뜨면 윗 방향으로
  // 바꾸고 다시 닫히면 아랫방향으로")
  const menusOpen = useHeaderOpen().owner === 'menu'
  return (
    <div className={styles.shell}>
      {/* data-app-header: screens with their own sticky bar read its height (cycle 10 S13) */}
      <div className={styles.headerBar} data-app-header="">
        <header className={styles.header}>
          {/* no tagline next to the logo on any width (cycle 10, user 2026-10-11) */}
          <div className={styles.start}>
            {/* the logo only goes to the main page, menus or not (user 2026-10-16, after using it: "좌상단 로고는 아래 탭
                늘려서 보이는 거랑 관계 없이 그냥 메인으로 옮겨주는 걸로만 해") */}
            <Link to="/" className={styles.brand}>
              <span className={styles.wordmark}>책첵</span>
            </Link>
            <HeaderMenu />
          </div>
          {/* the same header on every screen, the terms and privacy pages too (cycle 21 spec R1-6, cycle 20) */}
          <div className={styles.end}>
            {/* cycle 20: the lookup status light, left of 로그인 (spec R1-6). Empty until the first answer. Phones:
                dot · ▾ · 로그인 (user 2026-10-17: "폰인데 이거 위치 서로 바꾸는 게 나은 것 같아"; the mock-up had the dot
                next to 로그인) */}
            <span className={styles.statusSlot} data-lookup-status-slot="">
              <HeaderSignal />
            </span>
            {/* phones: a small ▾ saying the row opens the menus (plan 20 review #7, user "로그인 왼쪽" 2026-10-17); a tap on
                it goes through to the row's button */}
            <span className={styles.menuCaret} aria-hidden="true">
              {menusOpen ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
            </span>
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
