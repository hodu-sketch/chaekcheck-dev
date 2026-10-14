import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { fetchMe, logout, onSignedOut } from '../../api/auth'
import { setUsageLoginFeature } from '../../api/usageEvents'
import type { Me } from '../../api/types'

/**
 * Login state for the whole screen (handoff step 11): `checking` until the first `GET /api/v1/me` answers, then
 * `off` (login is switched off on the server: draw no login element), `signed-out` or `signed-in`.
 */
export type AuthState =
  | { status: 'checking' }
  | { status: 'off' }
  | { status: 'signed-out' }
  | { status: 'signed-in'; me: Me }

interface AuthContextValue {
  state: AuthState
  /** Asks the server again (after sign-up, sign-in or withdrawal). */
  reload: () => Promise<void>
  /** Logout: the server ends this browser's login and clears the cookies. */
  signOut: () => Promise<void>
  /** Marks the browser signed out without a request (after withdrawal). */
  markSignedOut: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'checking' })
  // Bumped by every local change (logout, withdrawal): an answer of a check started before it is stale
  const generation = useRef(0)

  const reload = useCallback(async () => {
    const started = generation.current
    try {
      const result = await fetchMe()
      if (generation.current !== started) return
      setState(result.state === 'signed-in' ? { status: 'signed-in', me: result.me } : { status: result.state })
    } catch {
      if (generation.current !== started) return
      // A server or network failure is not "signed out": keep the screen usable without login elements
      setState((current) => (current.status === 'checking' ? { status: 'off' } : current))
    }
  }, [])

  useEffect(() => {
    // A failed refresh anywhere (another request) means this browser is signed out, without a message
    onSignedOut(() => setState((current) => (current.status === 'off' ? current : { status: 'signed-out' })))
    void reload()
    return () => onSignedOut(null)
  }, [reload])

  useEffect(() => {
    // Usage reports carry the CSRF token only when the login feature is on (cycle 15)
    if (state.status !== 'checking') setUsageLoginFeature(state.status === 'off' ? 'off' : 'on')
  }, [state.status])

  const signOut = useCallback(async () => {
    await logout()
    generation.current++
    setState({ status: 'signed-out' })
  }, [])

  const markSignedOut = useCallback(() => {
    generation.current++
    setState({ status: 'signed-out' })
  }, [])

  const value = useMemo(() => ({ state, reload, signOut, markSignedOut }), [state, reload, signOut, markSignedOut])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (value === null) throw new Error('useAuth needs <AuthProvider>')
  return value
}

/** For screens also used without the provider (tests of the search screen): `null` there. */
export function useOptionalAuth(): AuthContextValue | null {
  return useContext(AuthContext)
}
