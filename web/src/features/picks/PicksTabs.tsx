import { useEffect, useRef } from 'react'
import { NavLink } from 'react-router'
import { PICKS_TABS } from './picksLists'
import styles from './PicksPage.module.css'

/** 「나에게 맞는 책」 (cycle 23 R1-1): the first tab, `/picks/for-you`. */
const FOR_YOU_TAB = { path: '/picks/for-you', label: '나에게 맞는 책' } as const

/**
 * The picks screen's tab row: 「나에게 맞는 책」, then the lists in their order. Phones swipe it; the current tab is
 * brought into view (user 2026-10-20).
 */
export function PicksTabs({ current }: { current: string }) {
  const tabsRef = useRef<HTMLElement>(null)
  useEffect(() => {
    const active = tabsRef.current?.querySelector<HTMLElement>('[aria-current="page"]')
    active?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
  }, [current])
  return (
    <nav ref={tabsRef} className={styles.tabs} aria-label="추천도서 목록">
      <NavLink to={FOR_YOU_TAB.path} className={styles.tab}>
        {FOR_YOU_TAB.label.replace(/ (?=\S+$)/, ' ')}
      </NavLink>
      {PICKS_TABS.map((tab) => (
        <NavLink key={tab.key} to={`/picks/${tab.key}`} className={styles.tab}>
          {/* the last two words stay together when a phone wraps the name ("KAIST / 사서 추천", user 2026-10-20) */}
          {tab.tabLabel.replace(/ (?=\S+$)/, ' ')}
        </NavLink>
      ))}
    </nav>
  )
}
