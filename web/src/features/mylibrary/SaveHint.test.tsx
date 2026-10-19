import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthContext'
import { loadPrefs, SETTINGS_STORAGE_KEY } from '../search/searchSettings'
import { SaveHint } from './SaveHint'

/** Cycle 22 R9 (AC-15): the one-line save hint before login. */

const HINT = '이 브라우저에만 저장됐어요. 로그인하면 다른 기기에서도 그대로예요'

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

let signedIn: boolean

beforeEach(() => {
  signedIn = false
  document.cookie = 'XSRF-TOKEN=csrf; path=/'
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url === '/api/v1/me') return signedIn ? json(200, { id: 7, loginMethods: ['KAKAO'], consents: [] }) : json(401, {})
      if (url === '/api/v1/auth/refresh') return json(401, {})
      if (url === '/api/v1/auth/providers') return json(200, ['KAKAO'])
      throw new Error(`unexpected ${url}`)
    }),
  )
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  localStorage.clear()
})

function renderHint(saved: boolean) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <MemoryRouter>
          <SaveHint saved={saved} />
          <p>loaded</p>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

/** Lets the login check answer. */
async function settle() {
  await waitFor(() => expect(vi.mocked(fetch)).toHaveBeenCalledWith('/api/v1/me', expect.anything()))
  await new Promise((resolve) => setTimeout(resolve, 0))
}

describe('SaveHint', () => {
  it('shows right after a save before login, with [카카오 로그인] and ×', async () => {
    renderHint(true)

    expect(await screen.findByText(HINT)).toBeTruthy()
    expect(screen.getByRole('button', { name: '카카오 로그인' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '저장 안내 닫기' })).toBeTruthy()
  })

  it('stays away before a save', async () => {
    renderHint(false)
    await settle()

    expect(screen.queryByText(HINT)).toBeNull()
  })

  it('× hides it in this browser for good', async () => {
    const first = renderHint(true)
    await userEvent.setup().click(await screen.findByRole('button', { name: '저장 안내 닫기' }))

    expect(screen.queryByText(HINT)).toBeNull()
    expect(loadPrefs().saveHintClosed).toBe(true)
    expect(JSON.parse(localStorage.getItem(SETTINGS_STORAGE_KEY)!).saveHintClosed).toBe(true)

    // a later visit and a later save: still hidden
    first.unmount()
    renderHint(true)
    await settle()
    expect(screen.queryByText(HINT)).toBeNull()
  })

  it('[카카오 로그인] opens the header login dialog', async () => {
    renderHint(true)
    await userEvent.setup().click(await screen.findByRole('button', { name: '카카오 로그인' }))

    const dialog = await screen.findByRole('dialog', { name: '책첵 로그인' })
    expect(dialog).toBeTruthy()
  })

  it('is never shown after login', async () => {
    signedIn = true
    renderHint(true)
    await settle()

    expect(screen.queryByText(HINT)).toBeNull()
  })
})
