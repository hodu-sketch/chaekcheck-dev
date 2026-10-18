/**
 * The visit key (ADR-0134): one random value per browser tab, made on first use and kept in `sessionStorage`, so it
 * ends when the tab closes and differs between tabs. Not a cookie. The server stores it next to the book views,
 * library clicks and screen events of this tab (header `X-Visit-Key` on the book page, body field `visitKey` of a
 * usage report) to see which books one visit opened together. It never identifies a person.
 */

const STORAGE_KEY = 'chaekcheck.visitKey'

export const VISIT_KEY_HEADER = 'X-Visit-Key'

/** 16 to 64 letters or digits, as the server accepts; a stored value of another shape is replaced. */
const SHAPE = /^[A-Za-z0-9]{16,64}$/

/** This tab's key when `sessionStorage` cannot be used; the same for the page's lifetime. */
let inMemory: string | null = null

/** The tab's visit key, made on first use; null only when no random value can be made. */
export function visitKey(): string | null {
  const stored = read()
  if (stored !== null) return stored
  if (inMemory === null) inMemory = make()
  if (inMemory !== null) write(inMemory)
  return inMemory
}

/** The header for a request that the server records as a book view; empty when there is no key. */
export function visitKeyHeaders(): Record<string, string> {
  const key = visitKey()
  return key === null ? {} : { [VISIT_KEY_HEADER]: key }
}

function make(): string | null {
  try {
    if (typeof crypto === 'undefined' || typeof crypto.randomUUID !== 'function') return null
    return crypto.randomUUID().replace(/-/g, '')
  } catch {
    return null
  }
}

function read(): string | null {
  try {
    const value = sessionStorage.getItem(STORAGE_KEY)
    return value !== null && SHAPE.test(value) ? value : null
  } catch {
    return null
  }
}

function write(value: string): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, value)
  } catch {
    // private windows or blocked storage: the in-memory value still serves this page
  }
}
