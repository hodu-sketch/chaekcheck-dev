import { useState, type ReactNode } from 'react'
import { useSearchParams } from 'react-router'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { useRegions } from '../region/RegionContext'
import { LibraryScopeChip, LibraryScopeLine, LibraryScopeSheet, type BoxFocus } from './LibraryScopeLine'
import type { VisitFilter } from '../../api/types'
import { loadPrefs, readVisit, savePrefs, writeVisit } from './searchSettings'

/** The regions and added libraries a screen asks with. */
export interface ConditionPlan {
  residence: number[]
  work: number[]
  libraryIds: number[]
}

/**
 * The condition card of the search screen on a screen that is not a search: the book page and the picks (user
 * 2026-10-20: "이 창에서도 지역 조건 수정할 수 있으면 좋겠어", "추천도서에도 지역설정 칸이 있었으면 해"). While the box or the
 * sheet is open the screen keeps the regions it was asked with (`plan`); closing it or [반영하기] uses what is saved
 * then, at once (`plan`, `visit`), with or without the [반영하기] button (`applyButton`); an address that says another 「온라인에서 가입 가능한 곳만 보기」 is changed too.
 */
export function useConditionCard({ applyButton = true }: { applyButton?: boolean } = {}): {
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
  const [frozen, setFrozen] = useState<{ plan: ConditionPlan; visit: VisitFilter } | null>(null)
  // the address first, else this browser's choice as it is now
  const visit = readVisit(params, visitPref)
  const freeze = () => setFrozen({ plan: live, visit })

  const changeVisit = (next: typeof visitPref) => {
    savePrefs({ visit: next })
    setVisitPref(next)
  }
  const keepOpenCount = (count: number) => {
    savePrefs({ openCount: count })
    setOpenCount(count)
  }
  const apply = () => {
    setFrozen(null)
    if (readVisit(params, visitPref) !== visitPref) setParams(writeVisit(visitPref, new URLSearchParams(params)))
  }
  const common = {
    visit: visitPref,
    onVisitChange: changeVisit,
    planCount: null,
    openCount,
    onOpenCount: keepOpenCount,
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
          if (!open) apply()
        }}
        focus="residence"
        visit={visitPref}
        onVisitChange={changeVisit}
        openCount={openCount}
        onOpenCount={keepOpenCount}
        onApply={applyButton ? apply : undefined}
      />
    </>
  ) : (
    <LibraryScopeLine
      {...common}
      box={box}
      onBoxChange={(next) => {
        if (next === null) apply()
        else freeze()
        setBox(next)
      }}
      // closing the box applies already; the button only closes it
      onApply={applyButton ? () => undefined : undefined}
    />
  )
  return { condition, plan: frozen?.plan ?? live, visit: frozen?.visit ?? visit }
}
