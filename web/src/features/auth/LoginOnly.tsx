import type { ReactNode } from 'react'
import { useAuth } from './AuthContext'

/** Draws the login screens only while login is on: with it off they render nothing (handoff step 13). */
export function LoginOnly({ children }: { children: ReactNode }) {
  const { state } = useAuth()
  return state.status === 'off' ? null : children
}
