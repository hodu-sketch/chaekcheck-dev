import { Dialog } from 'radix-ui'
import type { ReactNode } from 'react'
import { BottomSheet } from '../../components/BottomSheet'
import styles from './PopularWindow.module.css'

type Props = {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The window's name for screen readers. */
  title: string
  /** Phones (`(width <= 640px)`): a bottom sheet instead of the window in the middle. */
  phone: boolean
  /** `book`: S14, wider; `small`: S15. */
  size: 'book' | 'small'
  children: ReactNode
}

/**
 * The popular screen's windows (S14, S15): in the middle on PC and tablet with the page behind dimmed and blurred,
 * closed by ⓧ, a click outside or Escape; on phones the shared bottom sheet with a handle and ⓧ, where the back button
 * closes only the window (flow.md "폰 아래 창 공통", AC-18).
 */
export function PopularWindow({ open, onOpenChange, title, phone, size, children }: Props) {
  if (phone) {
    return (
      <BottomSheet open={open} onOpenChange={onOpenChange} title={title} closeButton closeOnBack>
        {children}
      </BottomSheet>
    )
  }
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className={styles.overlay} />
        <Dialog.Content className={styles.content} data-size={size} aria-describedby={undefined}>
          <Dialog.Title className="visually-hidden">{title}</Dialog.Title>
          <Dialog.Close className={styles.close} aria-label="닫기">
            <span aria-hidden="true">×</span>
          </Dialog.Close>
          {children}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
