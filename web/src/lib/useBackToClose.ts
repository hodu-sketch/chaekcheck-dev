import { useEffect, useRef } from 'react'

/** The key of this hook's mark in `history.state`; the rest of the state (the router's) is kept as it is. */
const KEY = 'chaekcheckSheet'

/**
 * Phones (cycle 10, flow.md S14 "폰 뒤로 가기 → 창만 닫힘"): while `active`, the window has its own history entry, so
 * the phone's back button closes only the window and the screen under it stays. Closing it another way (ⓧ, a tap
 * outside, pulling the handle down) takes that entry away again with `history.back()`. [안] method (cycle 10 step 6):
 * a `pushState` copy of the current state with a mark, not a router address, so the screen does not render again.
 * The entry is taken away a moment later, so React's strict-mode double effect reuses it instead of adding a second.
 */
export function useBackToClose(active: boolean, close: () => void) {
  const closeRef = useRef(close)
  const id = useRef('')
  const pendingBack = useRef<number | undefined>(undefined)

  useEffect(() => {
    closeRef.current = close
  })

  useEffect(() => {
    if (!active) return
    if (!id.current) id.current = Math.random().toString(36).slice(2)
    const mark = id.current
    if (pendingBack.current !== undefined) {
      window.clearTimeout(pendingBack.current)
      pendingBack.current = undefined
    }
    if (!isMarked(mark)) window.history.pushState({ ...(window.history.state ?? {}), [KEY]: mark }, '')
    const onPop = () => {
      if (!isMarked(mark)) closeRef.current()
    }
    window.addEventListener('popstate', onPop)
    return () => {
      window.removeEventListener('popstate', onPop)
      pendingBack.current = window.setTimeout(() => {
        pendingBack.current = undefined
        if (isMarked(mark)) window.history.back()
      }, 0)
    }
  }, [active])
}

/**
 * Whether the current history entry is a window's own entry (see `useBackToClose`). A link that leaves the screen
 * from inside such a window replaces that entry, so back from the next screen comes to the screen, not to the
 * closed window.
 */
export function onWindowEntry(): boolean {
  const state: unknown = window.history.state
  return typeof state === 'object' && state !== null && KEY in state
}

function isMarked(mark: string): boolean {
  const state: unknown = window.history.state
  return typeof state === 'object' && state !== null && (state as Record<string, unknown>)[KEY] === mark
}
