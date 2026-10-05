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
  it('defaults to the joinable side, no visit filter, 지금 빌릴 수 있는 책만, 기본 순서, no checks', () => {
    expect(loadPrefs(memoryStorage())).toEqual(DEFAULT_PREFS)
    expect(DEFAULT_PREFS.guestScope).toBe('joinable')
  })

  it('keeps each choice next to the settings and the 내 도서관 switch, under the same key', () => {
    const storage = memoryStorage()
    saveSettings({ view: 'books', direct: false }, storage)
    saveMyScope('all', storage)
    savePrefs({ guestScope: 'all', visit: 'none', filter: 'all', sort: 'newest' }, storage)
    savePrefs({ exact: ['title'], roles: ['translator'] }, storage)

    expect(loadPrefs(storage)).toEqual({
      guestScope: 'all',
      visit: 'none',
      filter: 'all',
      sort: 'newest',
      exact: ['title'],
      roles: ['translator'],
    })
    expect(loadMyScope(storage)).toBe('all')
    expect(JSON.parse(storage.getItem(SETTINGS_STORAGE_KEY)!).view).toBe('books')
  })

  it('drops unknown values one by one, and reads the defaults from broken or blocked storage', () => {
    const odd = memoryStorage({
      [SETTINGS_STORAGE_KEY]: JSON.stringify({ guestScope: 'x', visit: 'none', sort: 'title', exact: ['isbn', 'author'], roles: 'translator' }),
    })
    expect(loadPrefs(odd)).toEqual({ ...DEFAULT_PREFS, visit: 'none', exact: ['author'] })
    expect(loadPrefs(memoryStorage({ [SETTINGS_STORAGE_KEY]: '{not json' }))).toEqual(DEFAULT_PREFS)
    expect(loadPrefs(blocked)).toEqual(DEFAULT_PREFS)
    expect(() => savePrefs({ visit: 'none' }, blocked)).not.toThrow()
  })
})

describe('the address wins over the stored choice', () => {
  const params = (query: string) => new URLSearchParams(query)

  it('reads the filter, 방문 없이 가입 and the sort from the address first, then the stored one', () => {
    expect(readFilter(params(''), 'all')).toBe('all')
    expect(readFilter(params('filter=available'), 'all')).toBe('available')
    expect(readFilter(params('filter=all'), 'available')).toBe('all')

    expect(readVisit(params(''), 'none')).toBe('none')
    expect(readVisit(params('visit=any'), 'none')).toBe('any')
    expect(readVisit(params('visit=none'))).toBe('none')

    expect(readBookSearch(params('title=사랑'), 'newest')?.sort).toBe('newest')
    expect(readBookSearch(params('title=사랑&sort=oldest'), 'newest')?.sort).toBe('oldest')
  })

  it('writes 방문 없이 가입 only when on', () => {
    expect(writeVisit('none', params('')).toString()).toBe('visit=none')
    expect(writeVisit('any', params('visit=none')).toString()).toBe('')
  })
})
