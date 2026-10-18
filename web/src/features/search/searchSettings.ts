import type { LibraryScope, ResultFilter, VisitFilter } from '../../api/types'
import type { RegionSource } from '../region/RegionContext'

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

/** The whole stored object; settings and preferences share one key. */
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
 * Search preferences kept in this browser so they need not be set again every visit (user request 2026-10-05):
 * 방문 가입 빼기 and the result filter, plus what the first screen line remembers (cycle 22, D5). Same key as the
 * settings. The address still wins when it has a value; these only fill what it leaves out. The search texts themselves
 * are never stored. The sort and the 상세검색 checks (완전 일치, author roles) are not kept any more (cycle 08 R1, AC-1):
 * they belong to one search. There is no scope switch any more (cycle 22 R4-1, ADR-0135).
 */
export interface SearchPrefs {
  visit: VisitFilter
  filter: ResultFilter
  /**
   * How many libraries the last `joinable` search planned, and for which inputs (D5): shown only while
   * {@link planCountKey} of the current inputs is the same. Never kept for a `scope=all` search.
   */
  planCount: PlanCount | null
  /** How many "어디 살든 가입" libraries there are, from the last search that told; null = not known yet. */
  openCount: number | null
  /** The "이 브라우저에만 저장됐어요" hint was closed with ×. */
  saveHintClosed: boolean
}

export interface PlanCount {
  key: string
  count: number
}

export const DEFAULT_PREFS: SearchPrefs = {
  visit: 'any',
  filter: 'available',
  planCount: null,
  openCount: null,
  saveHintClosed: false,
}

/**
 * Values earlier versions kept under the same key (cycle 07: the sort and the 상세검색 checks; before cycle 22: the
 * scope switch before and after login); removed when read (cycle 08 AC-2, cycle 22 D5).
 */
const DROPPED_PREFS = ['sort', 'exact', 'roles', 'guestScope', 'myScope'] as const

function isCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0
}

function readPlanCount(value: unknown): PlanCount | null {
  if (!value || typeof value !== 'object') return null
  const { key, count } = value as Record<string, unknown>
  return typeof key === 'string' && isCount(count) ? { key, count } : null
}

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
      visit: stored.visit === 'none' ? 'none' : 'any',
      filter: stored.filter === 'all' ? 'all' : 'available',
      planCount: readPlanCount(stored.planCount),
      openCount: isCount(stored.openCount) ? stored.openCount : null,
      saveHintClosed: stored.saveHintClosed === true,
    }
  } catch {
    return DEFAULT_PREFS
  }
}

/** What a library plan depends on (D5); the `source` keeps a guest's and an account's counts apart. */
export interface PlanInputs {
  source: RegionSource
  residence: readonly number[]
  work: readonly number[]
  libraryIds: readonly number[]
  visit: VisitFilter
}

const sortedIds = (ids: readonly number[]) => [...ids].sort((a, b) => a - b).join(',')

/** The fingerprint `source|residence|work|libraryIds|visit` (D5); the order of the ids does not matter. */
export function planCountKey(inputs: PlanInputs): string {
  return [inputs.source, sortedIds(inputs.residence), sortedIds(inputs.work), sortedIds(inputs.libraryIds), inputs.visit].join('|')
}

/** The stored count when it was taken with the same inputs, else null (it hides itself once the inputs change). */
export function shownPlanCount(prefs: Pick<SearchPrefs, 'planCount'>, inputs: PlanInputs): number | null {
  return prefs.planCount && prefs.planCount.key === planCountKey(inputs) ? prefs.planCount.count : null
}

/**
 * The plan count to keep after a search, or undefined when nothing is to be kept: a `scope=all` search's count is
 * never stored (D5). Pass the result to {@link savePrefs}.
 */
export function planCountToKeep(scope: LibraryScope, inputs: PlanInputs, count: number): PlanCount | undefined {
  return scope === 'all' || !isCount(count) ? undefined : { key: planCountKey(inputs), count }
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

/** 방문 가입 빼기: `visit=none` / `visit=any` in the address wins; otherwise the fallback. */
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
