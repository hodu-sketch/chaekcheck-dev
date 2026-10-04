import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Route, Routes } from 'react-router'
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
import { BookPage } from './features/book/BookPage'
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
        <RegionProvider>
          <BrowserRouter>
            <Routes>
              <Route element={<AppLayout />}>
                <Route path="/" element={<SearchPage />} />
                <Route path="/search" element={<SearchPage />} />
                <Route path="/books/:bookId" element={<BookPage />} />
                <Route path="/auth/complete" element={<AuthCompletePage />} />
                <Route
                  path="/terms"
                  element={
                    <LoginOnly>
                      <LegalPage document="terms" />
                    </LoginOnly>
                  }
                />
                <Route
                  path="/privacy"
                  element={
                    <LoginOnly>
                      <LegalPage document="privacy" />
                    </LoginOnly>
                  }
                />
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
