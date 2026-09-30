/**
 * S4 settings (flow.md): how results are shown and whether to search the library sites directly. Kept in this browser
 * and in the address [확정]; these two values are the only thing stored, never the search itself [확정].
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

export function saveSettings(settings: SearchSettings, storage?: Storage): void {
  try {
    ;(storage ?? localStorage).setItem(SETTINGS_STORAGE_KEY, JSON.stringify(settings))
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
