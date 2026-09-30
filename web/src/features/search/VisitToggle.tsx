import { Toggle } from 'radix-ui'
import type { VisitFilter } from '../../api/types'
import styles from './LibraryScopeToggle.module.css'

type Props = {
  visit: VisitFilter
  onChange: (visit: VisitFilter) => void
}

/**
 * "방문 없이 가입" (user request 2026-09-29): on, visit-only libraries are not searched at all. Changing it searches
 * again from the first batch, like the scope toggle.
 */
export function VisitToggle({ visit, onChange }: Props) {
  return (
    <Toggle.Root
      pressed={visit === 'none'}
      onPressedChange={(pressed) => onChange(pressed ? 'none' : 'any')}
      className={`${styles.scope} ${styles.single}`}
    >
      <span className={styles.check} aria-hidden="true" />
      방문 없이 가입
    </Toggle.Root>
  )
}
