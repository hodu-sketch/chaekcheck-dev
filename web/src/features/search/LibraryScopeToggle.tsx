import { ToggleGroup } from 'radix-ui'
import type { LibraryScope } from '../../api/types'
import styles from './LibraryScopeToggle.module.css'

interface Props {
  scope: LibraryScope
  /** With regions the joinable libraries are "my regions + anyone"; without, only "anyone" is left. */
  hasRegions: boolean
  /** Signed in (cycle 06): the switch reads "전체 도서관 | 내 도서관" [확정]. */
  mine?: boolean
  onChange: (scope: LibraryScope) => void
}

/** Which libraries to search (C2-U-10). Changing it searches again from the first batch, like a region change. */
export function LibraryScopeToggle({ scope, hasRegions, mine = false, onChange }: Props) {
  const joinableLabel = mine ? '내 도서관' : hasRegions ? '가입 가능한 곳' : '누구나 가입'
  return (
    <ToggleGroup.Root
      type="single"
      value={scope}
      onValueChange={(value) => value && onChange(value as LibraryScope)}
      className={styles.scope}
      aria-label="조회할 도서관"
    >
      <ToggleGroup.Item value="all" className={styles.item}>
        전체 도서관
      </ToggleGroup.Item>
      <ToggleGroup.Item value="joinable" className={styles.item}>
        {joinableLabel}
      </ToggleGroup.Item>
    </ToggleGroup.Root>
  )
}
