import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import type { PlanParams } from '../../api/client'
import type { VisitFilter } from '../../api/types'
import { useRegions } from '../region/RegionContext'
import type { ConditionPlan } from '../search/useConditionCard'
import { loadPrefs, readVisit } from '../search/searchSettings'
import { readScope } from '../search/readScope'

/**
 * The plan of the librarian picks (cycle 10, AC-7): the same as the book screen's (`BookPage`), so 있음/없음 follows
 * what was chosen on the search screen: this browser's regions and added libraries before login, the account's after
 * (AC-16). `ready` is false while region ids are being checked; the request waits for it.
 */
export function usePicksPlan(
  asked?: ConditionPlan,
  askedVisit?: VisitFilter,
): { plan: PlanParams; ready: boolean; key: string } {
  const [params] = useSearchParams()
  const [prefs] = useState(() => loadPrefs())
  const scope = readScope(params)
  const visit = askedVisit ?? readVisit(params, prefs.visit)
  const { regions, libraryIds, ready } = useRegions()
  // the condition card's regions (kept while its box is open), else the saved ones
  const residenceKey = (asked?.residence ?? regions.residence).join(',')
  const workKey = (asked?.work ?? regions.work).join(',')
  const libraryKey = (asked?.libraryIds ?? libraryIds).join(',')
  const key = `${scope}|${visit}|${residenceKey}|${workKey}|${libraryKey}`
  const plan = useMemo<PlanParams>(
    () => ({
      scope,
      visit,
      residenceRegionIds: ids(residenceKey),
      workRegionIds: ids(workKey),
      libraryIds: ids(libraryKey),
    }),
    [scope, visit, residenceKey, workKey, libraryKey],
  )
  return { plan, ready, key }
}

function ids(joined: string): number[] {
  return joined ? joined.split(',').map(Number) : []
}
