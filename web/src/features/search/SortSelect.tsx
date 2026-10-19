import { DropdownMenu } from 'radix-ui'
import { useState } from 'react'
import type { BookSort } from '../../api/types'
import { SORT_LABELS, SORTS } from './searchCondition'
import styles from './SortSelect.module.css'
import { BottomSheet } from '../../components/BottomSheet'
import { CheckMark } from '../../components/icons'
import { useMediaQuery } from '../../lib/useMediaQuery'

interface Props {
  value: BookSort
  onChange?: (sort: BookSort) => void
  disabled?: boolean
  /** id of the text that says why it is disabled */
  describedBy?: string
}

/**
 * The sort menu above the results, drawn by the page so the open list has the same rounded look as the other
 * controls (user request 2026-10-05). The "?" that explains 기본 순서 is inside the list, under the orders (user request
 * 2026-10-05; a separate button beside the box before): it opens the explanation in place without closing the list.
 */
export function SortSelect(props: Props) {
  const phone = useMediaQuery('(width <= 640px)')
  return phone ? <SortSheet {...props} /> : <SortMenu {...props} />
}

function TriggerContent({ value }: { value: BookSort }) {
  return (
    <>
      {/* no down arrow (user 2026-10-20: "기본 순서 옆에 아랫방향 꺽쇠?화살표 좀 빼") */}
      {SORT_LABELS[value]}
    </>
  )
}

/**
 * Phones (user 2026-10-20: "기본 순서 모바일에서 선택할 때 아이폰양식으로"): the orders in the shared bottom sheet, one big row
 * each with a check on the chosen one; a tap chooses and closes. 「정렬 기준 보기」 opens the explanation in the sheet.
 */
function SortSheet({ value, onChange, disabled = false, describedBy }: Props) {
  const [open, setOpen] = useState(false)
  const [help, setHelp] = useState(false)
  const change = (next: boolean) => {
    setOpen(next)
    if (!next) setHelp(false)
  }
  if (disabled) {
    return (
      <button type="button" className={styles.trigger} aria-label={`정렬: ${SORT_LABELS[value]}`} aria-describedby={describedBy} disabled>
        <TriggerContent value={value} />
      </button>
    )
  }
  return (
    <BottomSheet
      open={open}
      onOpenChange={change}
      title="정렬"
      trigger={<TriggerContent value={value} />}
      triggerLabel={`정렬: ${SORT_LABELS[value]}`}
      triggerClassName={styles.trigger}
      closeOnBack
    >
      <div className={styles.sheet}>
        <h2 className={styles.sheetTitle} aria-hidden="true">
          정렬
        </h2>
        <div className={styles.sheetList} role="radiogroup" aria-label="정렬">
          {SORTS.map((sort) => (
            <button
              key={sort}
              type="button"
              role="radio"
              aria-checked={sort === value}
              className={styles.sheetItem}
              onClick={() => {
                onChange?.(sort)
                change(false)
              }}
            >
              {SORT_LABELS[sort]}
              {sort === value && (
                <span className={styles.check} aria-hidden="true">
                  <CheckMark />
                </span>
              )}
            </button>
          ))}
        </div>
        <button type="button" className={styles.sheetHelp} aria-expanded={help} onClick={() => setHelp((now) => !now)}>
          <span className={styles.helpMark} aria-hidden="true">
            ?
          </span>
          정렬 기준 보기
        </button>
        {help && <SortHelp />}
      </div>
    </BottomSheet>
  )
}

function SortMenu({ value, onChange, disabled = false, describedBy }: Props) {
  const [help, setHelp] = useState(false)
  return (
    <DropdownMenu.Root onOpenChange={(open) => !open && setHelp(false)}>
      <DropdownMenu.Trigger
        className={styles.trigger}
        aria-label={`정렬: ${SORT_LABELS[value]}`}
        aria-describedby={describedBy}
        disabled={disabled}
      >
        <TriggerContent value={value} />
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          className={styles.content}
          data-help={help || undefined}
          side="bottom"
          align="end"
          sideOffset={6}
          collisionPadding={12}
        >
          <DropdownMenu.RadioGroup value={value} onValueChange={(next) => onChange?.(next as BookSort)}>
            {SORTS.map((sort) => (
              <DropdownMenu.RadioItem key={sort} value={sort} className={styles.item}>
                {SORT_LABELS[sort]}
                <DropdownMenu.ItemIndicator className={styles.check} aria-hidden="true">
                  <CheckMark />
                </DropdownMenu.ItemIndicator>
              </DropdownMenu.RadioItem>
            ))}
          </DropdownMenu.RadioGroup>
          <DropdownMenu.Separator className={styles.separator} />
          <DropdownMenu.Item
            className={`${styles.item} ${styles.helpItem}`}
            aria-expanded={help}
            onSelect={(event) => {
              // keep the list open; the explanation opens under this line
              event.preventDefault()
              setHelp((open) => !open)
            }}
          >
            <span className={styles.helpMark} aria-hidden="true">
              ?
            </span>
            정렬 기준 보기
          </DropdownMenu.Item>
          {help && <SortHelp />}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  )
}

/** What "기본 순서" means (user request 2026-10-05). The order is the backend's (cycle 05 book sort). */
function SortHelp() {
  return (
    <div className={styles.helpBody}>
      <p className={styles.helpTitle}>기본 순서는 이렇게 정해요</p>
      <p>책끼리는 아래 순서로 비교하고, 앞에서 같으면 다음 기준을 봐요.</p>
      <ol className={styles.helpList}>
        <li>
          가장 쉽게 빌릴 수 있는 도서관에 있는 책
          <span className={styles.helpSub}>
            묶음(내 도서관 → 어디 살든 가입 → 그 밖) 안에서: 직접 추가 → 내 시군구 → 내 시도 → 방문 가입 → 가입 자격 확인 필요
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
    </div>
  )
}
