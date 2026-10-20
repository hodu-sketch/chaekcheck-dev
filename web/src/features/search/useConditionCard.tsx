import type { ReactNode } from 'react'
import { useSearchParams } from 'react-router'
import type { VisitFilter } from '../../api/types'
import { useRegions } from '../region/RegionContext'
import { ScopeNote } from './LibraryScopeLine'
import { loadPrefs, readVisit } from './searchSettings'

/** The regions and added libraries a screen asks with. */
export interface ConditionPlan {
  residence: number[]
  work: number[]
  libraryIds: number[]
}

/**
 * The line under the head of a screen that is not a search, the book page and the picks: what is asked with and a link
 * to the my page, where it is changed (user 2026-10-20: "검색창, 추천도서 창에서 지역 선택하는 거 싹 다 빼고"; a box with
 * the same choice as the search screen before). The screen asks with the saved regions, added libraries and 「온라인에서
 * 가입 가능한 곳만 보기」 (the address first).
 */
export function useConditionCard(): {
  condition: ReactNode
  plan: ConditionPlan
  visit: VisitFilter
} {
  const [params] = useSearchParams()
  const { regions, libraryIds } = useRegions()
  const plan: ConditionPlan = { residence: regions.residence, work: regions.work, libraryIds }
  return { condition: <ScopeNote />, plan, visit: readVisit(params, loadPrefs().visit) }
}
