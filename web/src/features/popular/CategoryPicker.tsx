import { Popover } from 'radix-ui'
import { useState } from 'react'
import type { PopularCategory } from '../../api/types'
import { BottomSheet } from '../../components/BottomSheet'
import styles from './PopularPage.module.css'

type Props = {
  categories: PopularCategory[]
  /** The category in the middle of the screen now (or the last one picked). */
  current: string
  onPick: (code: string) => void
  phone: boolean
}

/**
 * S13c (R5-1, R5-2, AC-5): [전체 ▾] opens the 17 categories in screen order, the current one bold. PC and tablet: a
 * list right under it (Escape or a click outside closes it); phones: the bottom sheet with ⓧ. Picking one closes the
 * list; the page moves to that category.
 */
export function CategoryPicker({ categories, current, onPick, phone }: Props) {
  const [open, setOpen] = useState(false)
  const currentName = categories.find((c) => c.code === current)?.name ?? categories[0]?.name ?? ''
  const label = `분류: ${currentName}`

  const pick = (code: string) => {
    setOpen(false)
    onPick(code)
  }
  const list = (
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
  )
  const face = (
    <>
      <span className={styles.pickerName}>{currentName}</span>
      <span className={styles.pickerMark} aria-hidden="true">
        ▾
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
          onClick={() => setOpen(true)}
        >
          {face}
        </button>
        <BottomSheet open={open} onOpenChange={setOpen} title="분류 고르기" closeButton closeOnBack>
          {list}
        </BottomSheet>
      </>
    )
  }
  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger className={styles.picker} aria-label={label}>
        {face}
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content className={styles.pickPopover} side="bottom" align="end" sideOffset={6} collisionPadding={12}>
          {list}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}
