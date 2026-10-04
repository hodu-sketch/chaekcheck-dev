import { Popover, Select } from 'radix-ui'
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
    <>
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
      {/* right of the sort (user request 2026-10-05) */}
      {!disabled && <SortHelp />}
    </>
  )
}

/** What "기본 순서" means, next to the sort (user request 2026-10-05). The order is the backend's (cycle 05 book sort). */
function SortHelp() {
  return (
    <Popover.Root>
      <Popover.Trigger className={styles.help} aria-label="정렬 기준 보기">
        <span aria-hidden="true">?</span>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content className={styles.helpContent} side="bottom" align="end" sideOffset={6} collisionPadding={12}>
          <p className={styles.helpTitle}>기본 순서는 이렇게 정해요</p>
          <p>책끼리는 아래 순서로 비교하고, 앞에서 같으면 다음 기준을 봐요.</p>
          <ol className={styles.helpList}>
            <li>
              가장 쉽게 빌릴 수 있는 도서관에 있는 책
              <span className={styles.helpSub}>
                직접 추가 → 내 시군구 → 내 시도 → 누구나 가입 → 방문 가입 → 가입 자격 확인 필요
              </span>
            </li>
            <li>제목이 검색어와 똑같은 책</li>
            <li>가진 도서관이 많은 책</li>
            <li>최근에 나온 책 (출판일을 모르면 뒤로)</li>
          </ol>
          <p>출판일 순으로 바꿔도 출판일이 같으면 기본 순서를 따라요.</p>
          <p>
            한 책 안의 도서관은 가입하기 쉬운 곳이 먼저예요. 그다음 ‘지금 빌릴 수 있어요’는 남은 권수가 많은 곳,
            ‘예약하고 기다려요’는 예약한 사람이 적은 곳이 먼저예요.
          </p>
          <Popover.Arrow className={styles.helpArrow} width={12} height={6} />
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}
