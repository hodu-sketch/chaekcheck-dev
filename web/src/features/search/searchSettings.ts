import type { LibraryScope, ResultFilter, VisitFilter } from '../../api/types'

/**
 * S4 settings (flow.md): how results are shown and whether to search the library sites directly. Kept in this browser
 * and in the address [확정]. The search texts are never stored [확정]; the search preferences below are (user request
 * 2026-10-05).
 */
export type ViewMode = 'instant' | 'books'

/** [안] reason shown next to what the direct site search cannot use (user: "흐리게 이것만"). */
export const DIRECT_REASON = '도서관 사이트 검색에서는 쓸 수 없어요'

export interface SearchSettings {
  /** instant = 바로 보기 (default [확정]), books = 책 먼저 고르기 */
  view: ViewMode
  /** 도서관에서 직접 찾기 (default off [안]) */
  direct: boolean
}

export const DEFAULT_SETTINGS: SearchSettings = { view: 'instant', direct: false }

/** [안] key name; the version suffix lets a later format change ignore old data. */
export const SETTINGS_STORAGE_KEY = 'chaekcheck.searchSettings.v1'

/** Stored settings; the defaults when storage cannot be used or holds something else [안]. */
export function loadSettings(storage?: Storage): SearchSettings {
  try {
    const raw = (storage ?? localStorage).getItem(SETTINGS_STORAGE_KEY)
    if (!raw) return DEFAULT_SETTINGS
    const parsed = JSON.parse(raw) as Partial<SearchSettings>
    return { view: parsed.view === 'books' ? 'books' : 'instant', direct: parsed.direct === true }
  } catch {
    return DEFAULT_SETTINGS
  }
}

/** The whole stored object; settings and the 내 도서관 switch share one key. */
function readStored(storage: Storage): Record<string, unknown> {
  try {
    const parsed: unknown = JSON.parse(storage.getItem(SETTINGS_STORAGE_KEY) ?? '{}')
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

export function saveSettings(settings: SearchSettings, storage?: Storage): void {
  try {
    const target = storage ?? localStorage
    target.setItem(SETTINGS_STORAGE_KEY, JSON.stringify({ ...readStored(target), ...settings }))
  } catch {
    // private mode or full storage: the address still carries the settings
  }
}

/**
 * The "전체 도서관 | 내 도서관" switch after login (cycle 06, spec R5, AC-16): the value the user chose, kept in this
 * browser across reloads; null until they choose. `joinable` = 내 도서관.
 */
export function loadMyScope(storage?: Storage): 'all' | 'joinable' | null {
  try {
    const value = readStored(storage ?? localStorage).myScope
    return value === 'all' || value === 'joinable' ? value : null
  } catch {
    return null
  }
}

export function saveMyScope(scope: 'all' | 'joinable', storage?: Storage): void {
  try {
    const target = storage ?? localStorage
    target.setItem(SETTINGS_STORAGE_KEY, JSON.stringify({ ...readStored(target), myScope: scope }))
  } catch {
    // storage blocked: the switch still works for this visit
  }
}

/**
 * The settings in effect: a value in the address wins over the stored one [안], so a shared address shows what its
 * sender saw. `view=books` / `view=instant` and `direct=on` / `direct=off`; a missing value falls back to storage.
 */
export function resolveSettings(params: URLSearchParams, stored: SearchSettings): SearchSettings {
  const view = params.get('view')
  const direct = params.get('direct')
  return {
    view: view === 'books' ? 'books' : view === 'instant' ? 'instant' : stored.view,
    direct: direct === 'on' ? true : direct === 'off' ? false : stored.direct,
  }
}

/** Writes the settings into the address: only values that differ from the defaults. */
export function writeSettings(settings: SearchSettings, into: URLSearchParams): URLSearchParams {
  if (settings.view === 'books') into.set('view', 'books')
  else into.delete('view')
  if (settings.direct) into.set('direct', 'on')
  else into.delete('direct')
  return into
}

/**
 * Search preferences kept in this browser so they need not be set again every visit (user request 2026-10-05): the
 * scope before login, 방문 없이 가입 and the result filter. Same key as the settings. The address still wins when it has
 * a value; these only fill what it leaves out. The search texts themselves are never stored. The sort and the 상세검색
 * checks (완전 일치, author roles) are not kept any more (cycle 08 R1, AC-1): they belong to one search.
 */
export interface SearchPrefs {
  /** The switch before login (after login it is `myScope`). Default: the joinable side (user request 2026-10-05). */
  guestScope: LibraryScope
  visit: VisitFilter
  filter: ResultFilter
}

export const DEFAULT_PREFS: SearchPrefs = {
  guestScope: 'joinable',
  visit: 'any',
  filter: 'available',
}

/** Values earlier versions kept under the same key (cycle 07); removed when read (AC-2). */
const DROPPED_PREFS = ['sort', 'exact', 'roles'] as const

/**
 * Stored preferences; each unknown or missing value falls back to its default, and so does blocked storage. Values
 * this version does not keep any more are removed from storage on the way.
 */
export function loadPrefs(storage?: Storage): SearchPrefs {
  try {
    const target = storage ?? localStorage
    const stored = readStored(target)
    if (DROPPED_PREFS.some((key) => key in stored)) {
      const kept = { ...stored }
      DROPPED_PREFS.forEach((key) => delete kept[key])
      target.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(kept))
    }
    return {
      guestScope: stored.guestScope === 'all' ? 'all' : 'joinable',
      visit: stored.visit === 'none' ? 'none' : 'any',
      filter: stored.filter === 'all' ? 'all' : 'available',
    }
  } catch {
    return DEFAULT_PREFS
  }
}

/** Keeps the given preferences, leaving the other stored values as they are. */
export function savePrefs(prefs: Partial<SearchPrefs>, storage?: Storage): void {
  try {
    const target = storage ?? localStorage
    target.setItem(SETTINGS_STORAGE_KEY, JSON.stringify({ ...readStored(target), ...prefs }))
  } catch {
    // storage blocked: the choice still works for this visit through the address
  }
}

/** 방문 없이 가입: `visit=none` / `visit=any` in the address wins; otherwise the stored choice. */
export function readVisit(params: URLSearchParams, fallback: VisitFilter = 'any'): VisitFilter {
  const value = params.get('visit')
  return value === 'none' ? 'none' : value === 'any' ? 'any' : fallback
}

/**
 * Sets `visit=none` when on, else removes it. A change is also stored, so a missing value then reads the same choice.
 */
export function writeVisit(visit: VisitFilter, into: URLSearchParams): URLSearchParams {
  if (visit === 'none') into.set('visit', 'none')
  else into.delete('visit')
  return into
}
