import { Select } from 'radix-ui'
import type { BookSort } from '../../api/types'
import { SORT_LABELS, SORTS } from './searchCondition'
import styles from './SortSelect.module.css'


interface Props {
  value: BookSort
  onChange?: (sort: BookSort) => void
  disabled?: boolean
  /** id of the text that says why it is disabled */
  describedBy?: string
}

/**
 * The sort menu above the results. Drawn by the page instead of the browser's own list, so the open list has the
 * same rounded look as the other controls (user request 2026-10-05).
 */
export function SortSelect({ value, onChange, disabled = false, describedBy }: Props) {
  return (
    <Select.Root value={value} onValueChange={(next) => onChange?.(next as BookSort)} disabled={disabled}>
      <Select.Trigger className={styles.trigger} aria-label="정렬" aria-describedby={describedBy}>
        <Select.Value />
        <Select.Icon className={styles.icon} aria-hidden="true">
          <svg width="12" height="8" viewBox="0 0 12 8">
            <path d="M1 1.5l5 5 5-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </Select.Icon>
      </Select.Trigger>
      <Select.Portal>
        <Select.Content className={styles.content} position="popper" side="bottom" align="end" sideOffset={6}>
          <Select.Viewport className={styles.viewport}>
            {SORTS.map((sort) => (
              <Select.Item key={sort} value={sort} className={styles.item}>
                <Select.ItemText>{SORT_LABELS[sort]}</Select.ItemText>
                <Select.ItemIndicator className={styles.check} aria-hidden="true">
                  ✓
                </Select.ItemIndicator>
              </Select.Item>
            ))}
          </Select.Viewport>
        </Select.Content>
      </Select.Portal>
    </Select.Root>
  )
}
