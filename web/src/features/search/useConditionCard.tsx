import { useState, type ReactNode } from 'react'
import { useSearchParams } from 'react-router'
import type { VisitFilter } from '../../api/types'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { useRegions } from '../region/RegionContext'
import { LibraryScopeChip, LibraryScopeLine, LibraryScopeSheet, SearchAgainHint, type BoxFocus } from './LibraryScopeLine'
import { loadPrefs, readVisit, savePrefs, writeVisit } from './searchSettings'

/** The regions and added libraries a screen asks with. */
export interface ConditionPlan {
  residence: number[]
  work: number[]
  libraryIds: number[]
}

/**
 * When the screen takes a changed condition: `close` as soon as the window closes (the picks, a cheap ask again), or
 * `searchAgain` only on [다시 찾기] in the line under the card (the book page asks every library again; user
 * 2026-10-20: "여기 검색에도 다른 것들처럼 정보 바꼈으니까 검색 새로하라고하고 가능한 버튼 만들어줘").
 */
export type ConditionApply = 'close' | 'searchAgain'

const sameIds = (a: number[], b: number[]) => a.length === b.length && a.every((id, i) => id === b[i])

/**
 * The condition card of the search screen on a screen that is not a search: the book page and the picks (user
 * 2026-10-20: "이 창에서도 지역 조건 수정할 수 있으면 좋겠어", "추천도서에도 지역설정 칸이 있었으면 해"). The screen asks with
 * `plan` and `visit`, which stay as they were while the window is open and change as `apply` says; no [반영하기] in the
 * window (user 2026-10-20: "지역 설정에 반영하기 싹 다 빼고"). An address that says another 「온라인에서 가입 가능한 곳만
 * 보기」 is changed too when the new condition is taken.
 */
export function useConditionCard({ apply: mode }: { apply: ConditionApply }): {
  condition: ReactNode
  plan: ConditionPlan
  visit: VisitFilter
} {
  const [params, setParams] = useSearchParams()
  const { regions, libraryIds } = useRegions()
  const phone = useMediaQuery('(width <= 640px)')
  const [visitPref, setVisitPref] = useState(() => loadPrefs().visit)
  const [openCount, setOpenCount] = useState(() => loadPrefs().openCount)
  const [box, setBox] = useState<BoxFocus | null>(null)
  const [sheetOpen, setSheetOpen] = useState(false)
  const live: ConditionPlan = { residence: regions.residence, work: regions.work, libraryIds }
  // the address first, else this browser's choice as it is now
  const visit = readVisit(params, visitPref)
  const [frozen, setFrozen] = useState<{ plan: ConditionPlan; visit: VisitFilter } | null>(null)
  const freeze = () => setFrozen((current) => current ?? { plan: live, visit })

  const changeVisit = (next: typeof visitPref) => {
    savePrefs({ visit: next })
    setVisitPref(next)
  }
  const keepOpenCount = (count: number) => {
    savePrefs({ openCount: count })
    setOpenCount(count)
  }
  const take = () => {
    setFrozen(null)
    if (readVisit(params, visitPref) !== visitPref) setParams(writeVisit(visitPref, new URLSearchParams(params)))
  }
  const closed = () => {
    if (mode === 'close') take()
  }
  const changed =
    frozen !== null &&
    (!sameIds(frozen.plan.residence, live.residence) ||
      !sameIds(frozen.plan.work, live.work) ||
      !sameIds(frozen.plan.libraryIds, live.libraryIds) ||
      frozen.visit !== visitPref)
  const common = {
    visit: visitPref,
    onVisitChange: changeVisit,
    planCount: null,
    openCount,
    onOpenCount: keepOpenCount,
    changedHint:
      mode === 'searchAgain' && changed && box === null && !sheetOpen ? <SearchAgainHint onClick={take} /> : undefined,
  }
  const condition = phone ? (
    <>
      <LibraryScopeChip
        {...common}
        onOpen={() => {
          freeze()
          setSheetOpen(true)
        }}
      />
      <LibraryScopeSheet
        open={sheetOpen}
        onOpenChange={(open) => {
          setSheetOpen(open)
          if (!open) closed()
        }}
        focus="residence"
        visit={visitPref}
        onVisitChange={changeVisit}
        openCount={openCount}
        onOpenCount={keepOpenCount}
      />
    </>
  ) : (
    <LibraryScopeLine
      {...common}
      box={box}
      onBoxChange={(next) => {
        if (next === null) closed()
        else freeze()
        setBox(next)
      }}
    />
  )
  return { condition, plan: frozen?.plan ?? live, visit: frozen?.visit ?? visit }
}
