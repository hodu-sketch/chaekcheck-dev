import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'

/**
 * Which header pop-up is open: the menus or the lookup status popover (cycle 20 handoff 1-12 [확정]: "메뉴와 말풍선은
 * 동시에 열리지 않습니다"). Whoever opens claims the place; the other one sees the owner change and closes. Esc still
 * reaches only the one that is open, which closes itself and returns the focus to where it was opened.
 */
export type HeaderPopup = 'menu' | 'status'

interface HeaderOpenValue {
  owner: HeaderPopup | null
  claim: (popup: HeaderPopup) => void
  release: (popup: HeaderPopup) => void
}

const HeaderOpenContext = createContext<HeaderOpenValue>({ owner: null, claim: () => {}, release: () => {} })

export function HeaderOpenProvider({ children }: { children: ReactNode }) {
  const [owner, setOwner] = useState<HeaderPopup | null>(null)
  const claim = useCallback((popup: HeaderPopup) => setOwner(popup), [])
  const release = useCallback((popup: HeaderPopup) => setOwner((current) => (current === popup ? null : current)), [])
  const value = useMemo(() => ({ owner, claim, release }), [owner, claim, release])
  return <HeaderOpenContext.Provider value={value}>{children}</HeaderOpenContext.Provider>
}

export function useHeaderOpen(): HeaderOpenValue {
  return useContext(HeaderOpenContext)
}
