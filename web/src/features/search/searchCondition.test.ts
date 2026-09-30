import { describe, expect, it } from 'vitest'
import { readBookSearch, readCondition, writeBookSearch, EMPTY_INPUT } from './searchCondition'
import { DEFAULT_SETTINGS, loadSettings, resolveSettings, saveSettings, SETTINGS_STORAGE_KEY, writeSettings } from './searchSettings'

const params = (search: string) => new URLSearchParams(search)

describe('catalog search in the address (ADR-0033)', () => {
  it('reads every filled field with its options', () => {
    expect(readBookSearch(params('title=행동&author=새폴스키&exact=title,isbn&roles=translator,other&sort=newest'))).toEqual({
      title: '행동',
      author: '새폴스키',
      publisher: '',
      exact: ['title'],
      roles: ['translator'],
      sort: 'newest',
    })
  })

  it('no field is the start screen; q is not read', () => {
    expect(readBookSearch(params(''))).toBeNull()
    expect(readBookSearch(params('q=사랑&view=books'))).toBeNull()
    expect(readBookSearch(params('title=&author= '))).toBeNull()
  })

  it('unknown sort falls back to the default order', () => {
    expect(readBookSearch(params('publisher=문학동네&sort=title'))?.sort).toBe('default')
  })

  it('writes only what is used: empty fields, exact of empty fields and roles without an author go', () => {
    const written = writeBookSearch(
      { ...EMPTY_INPUT, title: '행동', exact: ['title', 'author'], roles: ['editor'] },
      params('filter=available&author=old'),
    )
    expect(written.toString()).toBe('filter=available&title=%ED%96%89%EB%8F%99&exact=title')
  })

  it('round trips', () => {
    const input = { title: '행동 경제학', author: '탈러', publisher: '', exact: ['author' as const], roles: ['translator' as const], sort: 'oldest' as const }
    expect(readBookSearch(writeBookSearch(input))).toEqual(input)
  })

  it('the direct site search uses the first filled field: title, author, publisher [안]', () => {
    expect(readCondition(params('publisher=빅피시&author=김달'))).toEqual({ field: 'author', text: '김달' })
    expect(readCondition(params('title=사랑&publisher=빅피시'))).toEqual({ field: 'title', text: '사랑' })
  })
})

describe('settings (S4)', () => {
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

  it('defaults to 바로 보기 with direct search off', () => {
    expect(loadSettings(memoryStorage())).toEqual(DEFAULT_SETTINGS)
    expect(DEFAULT_SETTINGS).toEqual({ view: 'instant', direct: false })
  })

  it('saves and loads; broken storage gives the defaults', () => {
    const storage = memoryStorage()
    saveSettings({ view: 'books', direct: true }, storage)
    expect(loadSettings(storage)).toEqual({ view: 'books', direct: true })
    expect(loadSettings(memoryStorage({ [SETTINGS_STORAGE_KEY]: '{not json' }))).toEqual(DEFAULT_SETTINGS)
    const throwing = { getItem: () => { throw new Error('denied') }, setItem: () => { throw new Error('denied') } } as unknown as Storage
    expect(loadSettings(throwing)).toEqual(DEFAULT_SETTINGS)
    expect(() => saveSettings(DEFAULT_SETTINGS, throwing)).not.toThrow()
  })

  it('the address wins over storage; missing values come from storage [안]', () => {
    const stored = { view: 'books' as const, direct: true }
    expect(resolveSettings(params('view=instant&direct=off'), stored)).toEqual(DEFAULT_SETTINGS)
    expect(resolveSettings(params(''), stored)).toEqual(stored)
    expect(resolveSettings(params('view=books'), DEFAULT_SETTINGS)).toEqual({ view: 'books', direct: false })
  })

  it('writes only non-default values', () => {
    expect(writeSettings({ view: 'books', direct: true }, params('title=a')).toString()).toBe('title=a&view=books&direct=on')
    expect(writeSettings(DEFAULT_SETTINGS, params('view=books&direct=on')).toString()).toBe('')
  })
})
