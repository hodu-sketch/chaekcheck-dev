import { describe, expect, it } from 'vitest'
import { readFilter } from './resultFilter'
import { readBookSearch } from './searchCondition'
import {
  DEFAULT_PREFS,
  loadPrefs,
  planCountKey,
  planCountToKeep,
  readVisit,
  savePrefs,
  saveSettings,
  SETTINGS_STORAGE_KEY,
  shownPlanCount,
  writeVisit,
  type PlanInputs,
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
  it('defaults to no visit filter, 지금 빌릴 수 있는 책만, no counts, the save hint not closed', () => {
    expect(loadPrefs(memoryStorage())).toEqual(DEFAULT_PREFS)
    expect(DEFAULT_PREFS).toEqual({
      visit: 'any',
      filter: 'available',
      planCount: null,
      openCount: null,
      saveHintClosed: false,
    })
  })

  it('keeps each choice next to the settings, under the same key', () => {
    const storage = memoryStorage()
    saveSettings({ view: 'books', direct: false }, storage)
    savePrefs({ visit: 'none' }, storage)
    savePrefs({ filter: 'all' }, storage)
    savePrefs({ planCount: { key: 'browser|1114|||any', count: 68 }, openCount: 41, saveHintClosed: true }, storage)

    expect(loadPrefs(storage)).toEqual({
      visit: 'none',
      filter: 'all',
      planCount: { key: 'browser|1114|||any', count: 68 },
      openCount: 41,
      saveHintClosed: true,
    })
    expect(JSON.parse(storage.getItem(SETTINGS_STORAGE_KEY)!).view).toBe('books')
  })

  it('drops unknown values one by one, and reads the defaults from broken or blocked storage', () => {
    const odd = memoryStorage({
      [SETTINGS_STORAGE_KEY]: JSON.stringify({ visit: 'none', filter: 'some', openCount: 'many', saveHintClosed: 'yes' }),
    })
    expect(loadPrefs(odd)).toEqual({ ...DEFAULT_PREFS, visit: 'none' })
    expect(loadPrefs(memoryStorage({ [SETTINGS_STORAGE_KEY]: '{not json' }))).toEqual(DEFAULT_PREFS)
    expect(loadPrefs(memoryStorage({ [SETTINGS_STORAGE_KEY]: '[1,2]' }))).toEqual(DEFAULT_PREFS)
    expect(loadPrefs(blocked)).toEqual(DEFAULT_PREFS)
    expect(() => savePrefs({ visit: 'none' }, blocked)).not.toThrow()
  })

  it('reads a broken plan count or 어디 살든 가입 count as not known', () => {
    for (const planCount of [null, 'x', 5, { key: 1, count: 5 }, { key: 'k' }, { key: 'k', count: -1 }, { key: 'k', count: 1.5 }]) {
      const storage = memoryStorage({ [SETTINGS_STORAGE_KEY]: JSON.stringify({ planCount, openCount: -3 }) })
      expect(loadPrefs(storage)).toEqual(DEFAULT_PREFS)
    }
    const zero = memoryStorage({ [SETTINGS_STORAGE_KEY]: JSON.stringify({ planCount: { key: 'k', count: 0 }, openCount: 0 }) })
    expect(loadPrefs(zero)).toEqual({ ...DEFAULT_PREFS, planCount: { key: 'k', count: 0 }, openCount: 0 })
  })

  // cycle 08 AC-2: the sort and the 상세검색 checks an earlier version kept are removed; the rest stays
  it('removes the sort and the checks kept by earlier versions', () => {
    const storage = memoryStorage({
      [SETTINGS_STORAGE_KEY]: JSON.stringify({ view: 'books', filter: 'all', sort: 'newest', exact: ['title'], roles: ['translator'] }),
    })
    expect(loadPrefs(storage)).toEqual({ ...DEFAULT_PREFS, filter: 'all' })
    expect(JSON.parse(storage.getItem(SETTINGS_STORAGE_KEY)!)).toEqual({ view: 'books', filter: 'all' })
  })

  // cycle 22 R4-1, D5: no scope switch any more; the values it kept are removed
  it('removes the old scope switch values (guestScope, myScope)', () => {
    const storage = memoryStorage({
      [SETTINGS_STORAGE_KEY]: JSON.stringify({ direct: true, guestScope: 'all', myScope: 'all', visit: 'none' }),
    })
    const prefs = loadPrefs(storage)
    expect(prefs).toEqual({ ...DEFAULT_PREFS, visit: 'none' })
    expect('guestScope' in prefs).toBe(false)
    expect(JSON.parse(storage.getItem(SETTINGS_STORAGE_KEY)!)).toEqual({ direct: true, visit: 'none' })
  })
})

describe('the plan count fingerprint (D5)', () => {
  const inputs: PlanInputs = { source: 'browser', residence: [1114, 1102], work: [4110], libraryIds: [207, 12], visit: 'any' }

  it('is source|residence|work|libraryIds|visit, whatever the order of the ids', () => {
    expect(planCountKey(inputs)).toBe('browser|1102,1114|4110|12,207|any')
    expect(planCountKey({ ...inputs, residence: [1102, 1114], libraryIds: [12, 207] })).toBe(planCountKey(inputs))
    expect(planCountKey({ source: 'account', residence: [], work: [], libraryIds: [], visit: 'none' })).toBe('account||||none')
  })

  it('shows the count only while every part is the same', () => {
    const prefs = { planCount: { key: planCountKey(inputs), count: 68 } }
    expect(shownPlanCount(prefs, inputs)).toBe(68)
    expect(shownPlanCount(prefs, { ...inputs, source: 'account' })).toBeNull()
    expect(shownPlanCount(prefs, { ...inputs, residence: [1114] })).toBeNull()
    expect(shownPlanCount(prefs, { ...inputs, work: [] })).toBeNull()
    expect(shownPlanCount(prefs, { ...inputs, libraryIds: [12] })).toBeNull()
    expect(shownPlanCount(prefs, { ...inputs, visit: 'none' })).toBeNull()
    expect(shownPlanCount({ planCount: null }, inputs)).toBeNull()
  })

  it('never keeps the count of a scope=all search', () => {
    expect(planCountToKeep('joinable', inputs, 68)).toEqual({ key: planCountKey(inputs), count: 68 })
    expect(planCountToKeep('all', inputs, 376)).toBeUndefined()
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
