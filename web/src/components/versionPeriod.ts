import type { DocumentVersionInfo } from '../api/types'

/** "2026-10-20" → "2026-10-19", read from the string itself (no time-zone shift). */
export function dayBefore(date: string): string {
  const [year, month, day] = date.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, day - 1)).toISOString().slice(0, 10)
}

/**
 * The words for one version (user decision 2026-10-13): "A ~ 지금" for the current one, "A 적용 (같은 날 바뀜)" when
 * the next version came the same day, otherwise "A ~ (B-1일) 적용" where B is the day the next version took over.
 */
export function versionPeriod(version: DocumentVersionInfo): string {
  if (version.supersededOn === null) return `${version.effectiveOn} ~ 지금`
  if (version.supersededOn === version.effectiveOn) return `${version.effectiveOn} 적용 (같은 날 바뀜)`
  return `${version.effectiveOn} ~ ${dayBefore(version.supersededOn)} 적용`
}
