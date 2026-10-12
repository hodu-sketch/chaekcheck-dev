import { Popover } from 'radix-ui'
import { useRef, useState } from 'react'
import type { PickCategory } from '../../api/types'
import { BottomSheet } from '../../components/BottomSheet'
import { HELP_LINES, sourceLine } from './picksText'
import styles from './PicksPage.module.css'
import { ChevronDown } from '../../components/icons'

type Props = {
  categories: PickCategory[]
  /** The category in the middle of the screen now (or the last one picked). */
  current: string
  onPick: (code: string) => void
  phone: boolean
  /** For the source line under "추천 기준 보기" (S13i). */
  fetchedAt: string | null
}

/**
 * S13c (R5-1, R5-2, AC-5): [전체 ▾], floating at the bottom of the screen (user 2026-10-12), opens the fields in
 * screen order, the current one bold. PC and tablet: a list opening upward from it (Escape or a click outside
 * closes it); phones: the bottom sheet with ⓧ. Picking one closes the list; the page moves to that category.
 * S13i lives at the end of the list, like "정렬 기준 보기" in the sort menu (user 2026-10-12): "? 추천 기준 보기" opens
 * the explanation in place without closing the list (cycle 14 flow.md).
 */
export function CategoryPicker({ categories, current, onPick, phone, fetchedAt }: Props) {
  const [open, setOpen] = useState(false)
  const [help, setHelp] = useState(false)
  const helpRef = useRef<HTMLUListElement>(null)
  const changeOpen = (next: boolean) => {
    setOpen(next)
    if (!next) setHelp(false)
  }
  const currentName = categories.find((c) => c.code === current)?.name ?? categories[0]?.name ?? ''
  const label = `분류: ${currentName}`

  const pick = (code: string) => {
    changeOpen(false)
    onPick(code)
  }
  const list = (
    <>
      {/* only the categories scroll; "추천 기준 보기" stays in view under them (cycle 10 icon check) */}
      <div className={styles.pickScroll}>
        <ul className={styles.pickList} aria-label="분류">
          {categories.map((category) => (
            <li key={category.code}>
              <button
                type="button"
                className={styles.pickItem}
                aria-current={category.code === current ? 'true' : undefined}
                onClick={() => pick(category.code)}
              >
                {category.name}
              </button>
            </li>
          ))}
        </ul>
      </div>
      <div className={styles.pickSeparator} role="separator" />
      <button
        type="button"
        className={`${styles.pickItem} ${styles.pickHelpItem}`}
        aria-expanded={help}
        onClick={() => {
          setHelp((shown) => !shown)
          // the explanation opens under the last line of a scrolling list: bring it into view
          requestAnimationFrame(() => helpRef.current?.scrollIntoView?.({ block: 'nearest' }))
        }}
      >
        <span className={styles.pickHelpMark} aria-hidden="true">
          ?
        </span>
        추천 기준 보기
      </button>
      {help && (
        <ul ref={helpRef} className={styles.helpList}>
          {HELP_LINES.map((line) => (
            <li key={line}>{line}</li>
          ))}
          <li>{sourceLine(fetchedAt)}</li>
        </ul>
      )}
    </>
  )
  const face = (
    <>
      <span className={styles.pickerName}>{currentName}</span>
      <span className={styles.pickerMark} aria-hidden="true">
        <ChevronDown size={10} />
      </span>
    </>
  )

  if (phone) {
    return (
      <>
        <button
          type="button"
          className={styles.picker}
          aria-label={label}
          aria-haspopup="dialog"
          aria-expanded={open}
          onClick={() => changeOpen(true)}
        >
          {face}
        </button>
        <BottomSheet open={open} onOpenChange={changeOpen} title="분류 고르기" closeButton closeOnBack>
          {list}
        </BottomSheet>
      </>
    )
  }
  return (
    <Popover.Root open={open} onOpenChange={changeOpen}>
      <Popover.Trigger className={styles.picker} aria-label={label}>
        {face}
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content className={styles.pickPopover} side="top" align="center" sideOffset={8} collisionPadding={12}>
          {list}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}
