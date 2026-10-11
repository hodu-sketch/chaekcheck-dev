import { describe, expect, it } from 'vitest'
import { readFilter } from './resultFilter'
import { readBookSearch } from './searchCondition'
import {
  DEFAULT_PREFS,
  loadMyScope,
  loadPrefs,
  readVisit,
  saveMyScope,
  savePrefs,
  saveSettings,
  SETTINGS_STORAGE_KEY,
  writeVisit,
} from './searchSettings'

/** Search preferences kept in this browser (user request 2026-10-05). */

function memoryStorage(initial: Record<string, string> = {}): Storage {
  const data = new Map(Object.entries(initial))
  return {
    get length() {
      return data.size
    },
    clear: () => data.clear(),
    getItem: (key) => data.get(key) ?? null,
    key: (index) => [...data.keys()][index] ?? null,
    removeItem: (key) => void data.delete(key),
    setItem: (key, value) => void data.set(key, value),
  }
}

const blocked: Storage = {
  length: 0,
  clear: () => {},
  key: () => null,
  removeItem: () => {},
  getItem: () => {
    throw new Error('blocked')
  },
  setItem: () => {
    throw new Error('blocked')
  },
}

describe('loadPrefs / savePrefs', () => {
  it('defaults to the joinable side, no visit filter, 지금 빌릴 수 있는 책만', () => {
    expect(loadPrefs(memoryStorage())).toEqual(DEFAULT_PREFS)
    expect(DEFAULT_PREFS.guestScope).toBe('joinable')
  })

  it('keeps each choice next to the settings and the 내 도서관 switch, under the same key', () => {
    const storage = memoryStorage()
    saveSettings({ view: 'books', direct: false }, storage)
    saveMyScope('all', storage)
    savePrefs({ guestScope: 'all', visit: 'none' }, storage)
    savePrefs({ filter: 'all' }, storage)

    expect(loadPrefs(storage)).toEqual({ guestScope: 'all', visit: 'none', filter: 'all' })
    expect(loadMyScope(storage)).toBe('all')
    expect(JSON.parse(storage.getItem(SETTINGS_STORAGE_KEY)!).view).toBe('books')
  })

  it('drops unknown values one by one, and reads the defaults from broken or blocked storage', () => {
    const odd = memoryStorage({
      [SETTINGS_STORAGE_KEY]: JSON.stringify({ guestScope: 'x', visit: 'none', filter: 'some' }),
    })
    expect(loadPrefs(odd)).toEqual({ ...DEFAULT_PREFS, visit: 'none' })
    expect(loadPrefs(memoryStorage({ [SETTINGS_STORAGE_KEY]: '{not json' }))).toEqual(DEFAULT_PREFS)
    expect(loadPrefs(blocked)).toEqual(DEFAULT_PREFS)
    expect(() => savePrefs({ visit: 'none' }, blocked)).not.toThrow()
  })

  // cycle 08 AC-2: the sort and the 상세검색 checks an earlier version kept are removed; the rest stays
  it('removes the sort and the checks kept by earlier versions', () => {
    const storage = memoryStorage({
      [SETTINGS_STORAGE_KEY]: JSON.stringify({ view: 'books', myScope: 'all', filter: 'all', sort: 'newest',
        exact: ['title'], roles: ['translator'] }),
    })
    expect(loadPrefs(storage)).toEqual({ ...DEFAULT_PREFS, filter: 'all' })
    expect(JSON.parse(storage.getItem(SETTINGS_STORAGE_KEY)!)).toEqual({ view: 'books', myScope: 'all', filter: 'all' })
  })
})

describe('the address wins over the stored choice', () => {
  const params = (query: string) => new URLSearchParams(query)

  it('reads the filter and 방문 없이 가입 from the address first, then the stored one; the sort only from the address', () => {
    expect(readFilter(params(''), 'all')).toBe('all')
    expect(readFilter(params('filter=available'), 'all')).toBe('available')
    expect(readFilter(params('filter=all'), 'available')).toBe('all')

    expect(readVisit(params(''), 'none')).toBe('none')
    expect(readVisit(params('visit=any'), 'none')).toBe('any')
    expect(readVisit(params('visit=none'))).toBe('none')

    expect(readBookSearch(params('title=사랑'))?.sort).toBe('default')
    expect(readBookSearch(params('title=사랑&sort=oldest'))?.sort).toBe('oldest')
  })

  it('writes 방문 없이 가입 only when on', () => {
    expect(writeVisit('none', params('')).toString()).toBe('visit=none')
    expect(writeVisit('any', params('visit=none')).toString()).toBe('')
  })
})
