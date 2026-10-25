import { useState, type ReactNode } from 'react'
import { useLocation } from 'react-router'
import { AiSearchTab } from './AiSearchTab'
import { SearchModeSelect, type SearchMode } from './SearchModeSelect'
import { useAiStatus } from './useAiStatus'

export type SearchTab = SearchMode

/** Navigation state that opens the first screen on a given way (the chat's 「직접 찾기로」). */
export type SearchTabState = { searchTab?: SearchTab }

type Props = {
  /**
   * The direct search form, the same element on every screen. `leading` goes at the left end of its box: the AI/직접
   * menu on the first screen, nothing elsewhere. `below` goes under the box: nothing now (the example chips were taken
   * off the first screen, user 2026-10-25). `hidden` while AI is chosen. The form is returned without a wrapper:
   * the page's layout rules place it as a direct child of its search area.
   */
  direct: (leading: ReactNode, below: ReactNode, hidden: boolean) => ReactNode
  /** The first screen offers both ways; the results screen shows the direct search only. */
  showTabs: boolean
}

/**
 * The first screen's two ways to search, AI and 직접 (cycle 24 step 9, R1-1 [확정]: AI first). They were tabs above the
 * box; the user moved the choice into the box (2026-10-24: "검색창 안에 좌측에 AI 직접 이렇게 하고 AI가 기본값으로 하고
 * 셀렉트박스같은걸로 선택하게 해줘"): each way's box starts with the [AI ▾] menu. Each way keeps its text until the screen
 * is left (R1-3), because both stay mounted. With the AI search off (status 404) only the direct search shows. The
 * direct form keeps one place in the tree whatever is shown, so it is never remounted when the menu comes or goes.
 */
export function SearchTabs({ direct, showTabs }: Props) {
  const status = useAiStatus()
  const location = useLocation()
  const [mode, setMode] = useState<SearchMode>(() => (location.state as SearchTabState | null)?.searchTab ?? 'ai')
  const both = showTabs && status !== 'off'
  const shown: SearchMode = both ? mode : 'direct'

  const choose = (next: SearchMode) => {
    setMode(next)
    // the chosen way's text box takes the focus once it shows
    requestAnimationFrame(() => document.querySelector<HTMLInputElement>(`[data-search-way="${next}"] input`)?.focus())
  }

  const menu = both ? <SearchModeSelect value={shown} onChange={choose} /> : null

  return (
    <>
      {both && (
        <div data-search-way="ai" hidden={shown !== 'ai'}>
          <AiSearchTab leading={menu} />
        </div>
      )}
      {direct(shown === 'direct' ? menu : null, null, shown !== 'direct')}
    </>
  )
}
