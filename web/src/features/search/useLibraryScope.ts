import { useState } from 'react'
import type { SetURLSearchParams } from 'react-router'
import type { LibraryScope } from '../../api/types'
import { useOptionalMyLibrary } from '../mylibrary/MyLibraryContext'
import { useRegions } from '../region/RegionContext'
import { loadMyScope, loadPrefs, saveMyScope, savePrefs } from './searchSettings'

/**
 * Which libraries the search uses (C2-U-10, cycle 06 spec R5).
 * <ul>
 * <li>Before login: a `scope` in the address wins; otherwise the value chosen in this browser; otherwise the joinable
 * side (user request 2026-10-05, 전체 before). A change is kept in this browser; the address carries `scope` only
 * when it is not the default.</li>
 * <li>After login, while 내 도서관 is loading or failed: 전체 [확정] (flow S1).</li>
 * <li>After login: a `scope` in the address wins (a shared address shows what its sender saw); otherwise the value
 * chosen in this browser; otherwise 내 도서관 (user request 2026-10-05; before: 내 도서관 only when the account had
 * one, AC-16). Changing the switch keeps the choice in this browser and drops `scope` from the address (dev
 * decision, reported).</li>
 * </ul>
 */
export function useLibraryScope(params: URLSearchParams, setParams?: SetURLSearchParams) {
  const { source } = useRegions()
  const myLibrary = useOptionalMyLibrary()
  const [stored, setStored] = useState(() => loadMyScope())
  const [guestStored, setGuestStored] = useState(() => loadPrefs().guestScope)
  const address = params.get('scope')
  const mine = source === 'account'

  let scope: LibraryScope
  if (!mine) {
    scope = address === 'joinable' || address === 'all' ? address : guestStored
  } else if (myLibrary?.status !== 'ready') {
    scope = 'all'
  } else if (address === 'joinable' || address === 'all') {
    scope = address
  } else {
    scope = stored ?? 'joinable'
  }

  const change = (next: LibraryScope) => {
    const updated = new URLSearchParams(params)
    if (mine) {
      saveMyScope(next)
      setStored(next)
      updated.delete('scope')
    } else {
      savePrefs({ guestScope: next })
      setGuestStored(next)
      if (next === 'all') updated.set('scope', 'all')
      else updated.delete('scope')
    }
    setParams?.(updated, { replace: true })
  }

  /** The `scope` to carry into a new address (a new search, a book link): only what the address already had. */
  const addressScope = address === 'joinable' || address === 'all' ? address : null
  return { scope, mine, change, addressScope }
}

/** The words on the joinable side of the switch; also what the phone settings sheet shows for "찾을 도서관". */
export function joinableLabel(hasRegions: boolean, mine: boolean): string {
  return mine ? '내 도서관' : hasRegions ? '가입 가능한 곳' : '누구나 가입'
}
