import { Toggle } from 'radix-ui'
import type { VisitFilter } from '../../api/types'
import styles from './VisitToggle.module.css'

type Props = {
  visit: VisitFilter
  onChange: (visit: VisitFilter) => void
}

/**
 * "방문 없이 가입" (user request 2026-09-29): on, visit-only libraries are not searched. Since cycle 22 (D6) the switch
 * only keeps the choice in this browser; the next [검색] writes it into the address, and the results read it there.
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
