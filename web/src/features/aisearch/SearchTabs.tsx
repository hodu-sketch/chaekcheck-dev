import { useState, type ReactNode } from 'react'
import { AI_TEXT } from './aiSearchText'
import { AiSearchTab } from './AiSearchTab'
import { useAiStatus } from './useAiStatus'
import styles from './SearchTabs.module.css'

type Tab = 'ai' | 'direct'

/**
 * The first screen's two tabs [AI] [직접] (cycle 24 step 9, R1-1 [확정]: AI on the left, open first). The direct tab is
 * the search as it was. Each tab keeps its text until the screen is left (R1-3), because both stay mounted. With the
 * AI search off (status 404) the AI tab is not shown and the direct tab is open.
 */
export function SearchTabs({ direct }: { direct: ReactNode }) {
  const status = useAiStatus()
  const [tab, setTab] = useState<Tab>('ai')
  const [aiText, setAiText] = useState('')
  const shown: Tab = status === 'off' ? 'direct' : tab

  if (status === 'off') return <>{direct}</>

  return (
    <div className={styles.tabs}>
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
            onKeyDown={(event) => {
              if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
                const next: Tab = value === 'ai' ? 'direct' : 'ai'
                setTab(next)
                document.getElementById(`search-tab-${next}`)?.focus()
              }
            }}
          >
            {value === 'ai' ? AI_TEXT.tabAi : AI_TEXT.tabDirect}
          </button>
        ))}
      </div>
      <div id="search-panel-ai" role="tabpanel" aria-labelledby="search-tab-ai" hidden={shown !== 'ai'}>
        <AiSearchTab text={aiText} onTextChange={setAiText} />
      </div>
      <div id="search-panel-direct" role="tabpanel" aria-labelledby="search-tab-direct" hidden={shown !== 'direct'}>
        {direct}
      </div>
    </div>
  )
}
