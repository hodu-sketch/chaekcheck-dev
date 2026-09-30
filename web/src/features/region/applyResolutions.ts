import type { RegionResolution } from '../../api/types'
import { direction, subject } from '../../lib/josa'
import type { StoredRegions } from './regionStorage'

export type RegionNotice =
  | { kind: 'replaced'; message: string }
  | { kind: 'split'; message: string }

export interface ResolutionOutcome {
  regions: StoredRegions
  notices: RegionNotice[]
  changed: boolean
}

/**
 * Applies resolution results to the stored regions (spec 5.8, AC-20):
 * REPLACED -> swap in the new id and tell the user; SPLIT -> drop it and ask to choose again;
 * UNKNOWN -> drop it quietly [안]; ACTIVE -> keep.
 */
export function applyResolutions(regions: StoredRegions, resolutions: RegionResolution[]): ResolutionOutcome {
  const byId = new Map(resolutions.map((r) => [r.id, r]))
  const notices: RegionNotice[] = []
  const noticed = new Set<number>()
  let changed = false

  const mapField = (ids: number[]): number[] => {
    const next: number[] = []
    for (const id of ids) {
      const resolution = byId.get(id)
      if (!resolution || resolution.status === 'ACTIVE') {
        next.push(id)
        continue
      }
      changed = true
      if (resolution.status === 'REPLACED' && resolution.replacements.length === 1) {
        const replacement = resolution.replacements[0]
        next.push(replacement.id)
        if (!noticed.has(id) && resolution.label) {
          notices.push({
            kind: 'replaced',
            message: `행정구역 변경으로 ${subject(resolution.label)} ${direction(replacement.label)} 바뀌었습니다`,
          })
        }
      } else if (resolution.status === 'SPLIT' && resolution.label && !noticed.has(id)) {
        notices.push({
          kind: 'split',
          message: `${subject(resolution.label)} 여러 지역으로 나뉘었습니다. 내 지역을 다시 골라 주세요`,
        })
      }
      noticed.add(id)
    }
    return [...new Set(next)]
  }

  return { regions: { residence: mapField(regions.residence), work: mapField(regions.work) }, notices, changed }
}
