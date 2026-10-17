/**
 * The search and book screens tell the header light about a 503 `server_busy` through this small module, so that
 * `blocked.ts` and the hooks need no React context (cycle 20 handoff 1-12, spec R2: red at once, until the next
 * status answer). The provider subscribes; without a provider (tests) a report goes nowhere.
 */
type Listener = () => void

const listeners = new Set<Listener>()

export function signalServerBusy(): void {
  for (const listener of listeners) listener()
}

export function subscribeServerBusy(listener: Listener): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
