import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import './styles/fonts.css'
import './styles/global.css'
import { AppLayout } from './components/AppLayout'
import { AuthProvider } from './features/auth/AuthContext'
import { AuthCompletePage } from './features/auth/AuthCompletePage'
import { LegalPage } from './features/auth/LegalPage'
import { LoginOnly } from './features/auth/LoginOnly'
import { NoticeProvider } from './features/auth/NoticeContext'
import { SignupPage } from './features/auth/SignupPage'
import { ErrorBoundary } from './components/ErrorBoundary'
import { ErrorFallback } from './components/ErrorFallback'
import { AboutPage } from './features/about/AboutPage'
import { BookPage } from './features/book/BookPage'
import { MyLibrariesPage } from './features/mylibrary/MyLibrariesPage'
import { MyLibraryProvider } from './features/mylibrary/MyLibraryContext'
import { NoticePage } from './features/notices/NoticePage'
import { NoticesPage } from './features/notices/NoticesPage'
import { PicksPage } from './features/picks/PicksPage'
import { RegionProvider } from './features/region/RegionContext'
import { SearchPage } from './features/search/SearchPage'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
})

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <NoticeProvider>
        <MyLibraryProvider>
        <RegionProvider>
          <BrowserRouter>
            <Routes>
              <Route element={<AppLayout />}>
                <Route path="/" element={<SearchPage />} />
                <Route path="/search" element={<SearchPage />} />
                <Route path="/books/:bookId" element={<BookPage />} />
                {/* cycle 10, 14 (S13 librarian picks): public, no login (R7-3); cycle 21: one screen per list
                    (ADR-0126), the old address goes to the librarian picks. TODO(cycle 21 step 9-2/9-3): the
                    screen still reads the old API whatever the list */}
                <Route path="/picks" element={<Navigate to="/picks/librarian" replace />} />
                <Route path="/picks/:listKey" element={<PicksPage />} />
                <Route path="/auth/complete" element={<AuthCompletePage />} />
                <Route path="/about" element={<AboutPage />} />
                <Route
                  path="/terms"
                  element={
                    <LoginOnly>
                      <LegalPage document="terms" />
                    </LoginOnly>
                  }
                />
                {/* open whether login is on or off (cycle 16, ADR-0100); /terms stays login-only */}
                <Route path="/privacy" element={<LegalPage document="privacy" />} />
                {/* cycle 16 (S16, S17): public, no login; reached from the footer only */}
                <Route path="/notices" element={<NoticesPage />} />
                <Route path="/notices/:noticeId" element={<NoticePage />} />
                {/* open before login and with login off too (cycle 07, spec R3-6): saved in this browser then */}
                <Route path="/my-libraries" element={<MyLibrariesPage />} />
                <Route
                  path="/signup"
                  element={
                    <LoginOnly>
                      <SignupPage />
                    </LoginOnly>
                  }
                />
              </Route>
            </Routes>
          </BrowserRouter>
        </RegionProvider>
        </MyLibraryProvider>
        </NoticeProvider>
      </AuthProvider>
    </QueryClientProvider>
  )
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* last line of defense outside the layout; the layout has its own boundary for the screen */}
    <ErrorBoundary fallback={() => <ErrorFallback onRetry={() => location.reload()} retryLabel="새로고침" />}>
      <App />
    </ErrorBoundary>
  </StrictMode>,
)
