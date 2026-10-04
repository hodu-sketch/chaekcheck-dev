import { useState } from 'react'
import type { SetURLSearchParams } from 'react-router'
import type { LibraryScope } from '../../api/types'
import { useOptionalMyLibrary } from '../mylibrary/MyLibraryContext'
import { useRegions } from '../region/RegionContext'
import { loadMyScope, saveMyScope } from './searchSettings'

/**
 * Which libraries the search uses (C2-U-10, cycle 06 spec R5).
 * <ul>
 * <li>Before login: the address only (`scope=joinable`), as in cycle 05.</li>
 * <li>After login, while 내 도서관 is loading or failed: 전체 [확정] (flow S1).</li>
 * <li>After login: a `scope` in the address wins (a shared address shows what its sender saw); otherwise the value
 * chosen in this browser; otherwise 내 도서관 when the account has one [확정] (AC-16). Changing the switch keeps the
 * choice in this browser and drops `scope` from the address (dev decision, reported).</li>
 * </ul>
 */
export function useLibraryScope(params: URLSearchParams, setParams?: SetURLSearchParams) {
  const { source, regions, libraryIds } = useRegions()
  const myLibrary = useOptionalMyLibrary()
  const [stored, setStored] = useState(() => loadMyScope())
  const address = params.get('scope')
  const mine = source === 'account'

  let scope: LibraryScope
  if (!mine) {
    scope = address === 'joinable' ? 'joinable' : 'all'
  } else if (myLibrary?.status !== 'ready') {
    scope = 'all'
  } else if (address === 'joinable' || address === 'all') {
    scope = address
  } else {
    const hasMyLibrary = regions.residence.length > 0 || regions.work.length > 0 || libraryIds.length > 0
    scope = stored ?? (hasMyLibrary ? 'joinable' : 'all')
  }

  const change = (next: LibraryScope) => {
    const updated = new URLSearchParams(params)
    if (mine) {
      saveMyScope(next)
      setStored(next)
      updated.delete('scope')
    } else if (next === 'joinable') {
      updated.set('scope', 'joinable')
    } else {
      updated.delete('scope')
    }
    setParams?.(updated, { replace: true })
  }

  /** The `scope` to carry into a new address (a new search, a book link): only what the address already had. */
  const addressScope = address === 'joinable' || address === 'all' ? address : null
  return { scope, mine, change, addressScope }
}
