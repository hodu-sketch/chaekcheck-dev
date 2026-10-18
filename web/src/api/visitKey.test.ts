import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/** ADR-0134: one random key per tab, in sessionStorage, 32 letters or digits, never a cookie. */

const STORAGE_KEY = 'chaekcheck.visitKey'

/** A fresh module, as a newly opened page gets. */
async function freshModule() {
  vi.resetModules()
  return import('./visitKey')
}

beforeEach(() => sessionStorage.clear())

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

describe('visitKey', () => {
  it('is made on first use, kept in sessionStorage and the same afterwards', async () => {
    const { visitKey, visitKeyHeaders } = await freshModule()

    const key = visitKey()

    expect(key).toMatch(/^[0-9a-f]{32}$/)
    expect(sessionStorage.getItem(STORAGE_KEY)).toBe(key)
    expect(visitKey()).toBe(key)
    expect(visitKeyHeaders()).toEqual({ 'X-Visit-Key': key })
    expect(document.cookie).not.toContain(key as string)
  })

  it('a page opened later in the same tab reads the stored key; another tab (empty storage) gets its own', async () => {
    sessionStorage.setItem(STORAGE_KEY, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ012345')
    const samePage = await freshModule()
    expect(samePage.visitKey()).toBe('ABCDEFGHIJKLMNOPQRSTUVWXYZ012345')

    sessionStorage.clear()
    const otherTab = await freshModule()
    const other = otherTab.visitKey()
    expect(other).toMatch(/^[0-9a-f]{32}$/)
    expect(other).not.toBe('ABCDEFGHIJKLMNOPQRSTUVWXYZ012345')
  })

  it('a stored value of another shape is replaced', async () => {
    sessionStorage.setItem(STORAGE_KEY, 'a1b2c3d4-e5f6-0718-a1b2-c3d4e5f60718')
    const { visitKey } = await freshModule()

    const key = visitKey()

    expect(key).toMatch(/^[0-9a-f]{32}$/)
    expect(sessionStorage.getItem(STORAGE_KEY)).toBe(key)
  })

  it('without sessionStorage the key still holds for the page; without randomUUID there is none and no header', async () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })
    try {
      const { visitKey } = await freshModule()
      const key = visitKey()
      expect(key).toMatch(/^[0-9a-f]{32}$/)
      expect(visitKey()).toBe(key)
    } finally {
      getItem.mockRestore()
      setItem.mockRestore()
    }

    vi.stubGlobal('crypto', {})
    const { visitKey, visitKeyHeaders } = await freshModule()
    expect(visitKey()).toBeNull()
    expect(visitKeyHeaders()).toEqual({})
  })
})
