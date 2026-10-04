import { Popover } from 'radix-ui'
import type { ReactNode } from 'react'
import styles from './ScopeSummary.module.css'

interface Props {
  /** Button text, e.g. "내 도서관 관리". */
  label: string
  /** Panel title, e.g. "내 도서관". */
  title: string
  children: ReactNode
}

/**
 * The button on the scope switch line that opens the parts the search uses (user request 2026-10-05: the box under
 * the search field moves behind "내 도서관 관리"). Rows and the action come from the caller.
 */
export function ScopeSummary({ label, title, children }: Props) {
  return (
    <Popover.Root>
      <Popover.Trigger className={styles.trigger}>
        {label}
        <span className={styles.caret} aria-hidden="true">
          <svg width="10" height="7" viewBox="0 0 12 8">
            <path d="M1 1.5l5 5 5-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </span>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content className={styles.panel} side="bottom" align="start" sideOffset={8} collisionPadding={16}>
          <p className={styles.title}>{title}</p>
          {children}
          <Popover.Arrow className={styles.arrow} width={12} height={6} />
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}

/** One "거주 서울 마포구" row in the panel. */
export function ScopeRow({ kind, children }: { kind: string; children: ReactNode }) {
  return (
    <p className={styles.row}>
      <span className={styles.kind}>{kind}</span>
      <span className={styles.value}>{children}</span>
    </p>
  )
}
