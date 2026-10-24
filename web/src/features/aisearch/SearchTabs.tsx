import { useState, type KeyboardEvent, type ReactNode } from 'react'
import { useLocation } from 'react-router'
import { AI_TEXT } from './aiSearchText'
import { AiSearchTab } from './AiSearchTab'
import { useAiStatus } from './useAiStatus'
import styles from './SearchTabs.module.css'

export type SearchTab = 'ai' | 'direct'

/** Navigation state that opens the first screen on a given tab (the chat's 「직접 찾기로」). */
export type SearchTabState = { searchTab?: SearchTab }

type Props = {
  /** The direct search form, the same element on every screen. */
  direct: ReactNode
  /** The first screen shows the tabs; the results screen shows the direct search only. */
  showTabs: boolean
}

/**
 * The first screen's two tabs [AI] [직접] (cycle 24 step 9, R1-1 [확정]: AI on the left, open first). The direct tab is
 * the search as it was. Each tab keeps its text until the screen is left (R1-3), because both stay mounted. With the
 * AI search off (status 404) the AI tab is not shown and the direct tab is open. The direct panel keeps one place in
 * the tree whatever is shown, so the form is never remounted (and keeps its focus) when the tabs come or go.
 */
export function SearchTabs({ direct, showTabs }: Props) {
  const status = useAiStatus()
  const location = useLocation()
  const [tab, setTab] = useState<SearchTab>(() => (location.state as SearchTabState | null)?.searchTab ?? 'ai')
  const tabs = showTabs && status !== 'off'
  const shown: SearchTab = tabs ? tab : 'direct'

  const handleKeyDown = (event: KeyboardEvent, value: SearchTab) => {
    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return
    event.preventDefault()
    const next: SearchTab = value === 'ai' ? 'direct' : 'ai'
    setTab(next)
    document.getElementById(`search-tab-${next}`)?.focus()
  }

  return (
    <div className={tabs ? styles.tabs : undefined}>
      {tabs && (
        <div className={styles.tabList} role="tablist" aria-label="찾는 방법">
          {(['ai', 'direct'] as const).map((value) => (
            <button
              key={value}
              type="button"
              role="tab"
              id={`search-tab-${value}`}
              aria-selected={shown === value}
              aria-controls={`search-panel-${value}`}
              tabIndex={shown === value ? 0 : -1}
              className={styles.tab}
              onClick={() => setTab(value)}
              onKeyDown={(event) => handleKeyDown(event, value)}
            >
              {value === 'ai' ? AI_TEXT.tabAi : AI_TEXT.tabDirect}
            </button>
          ))}
        </div>
      )}
      {tabs && (
        <div id="search-panel-ai" role="tabpanel" aria-labelledby="search-tab-ai" hidden={shown !== 'ai'}>
          <AiSearchTab />
        </div>
      )}
      <div
        id="search-panel-direct"
        role={tabs ? 'tabpanel' : undefined}
        aria-labelledby={tabs ? 'search-tab-direct' : undefined}
        hidden={shown !== 'direct'}
      >
        {direct}
      </div>
    </div>
  )
}
