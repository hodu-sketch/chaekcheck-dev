import { Dialog } from 'radix-ui'
import { useState, type ReactNode } from 'react'
import { useBackToClose } from '../lib/useBackToClose'
import styles from './BottomSheet.module.css'

/** How far down the handle must be pulled to close the sheet [안] (cycle 08). */
export const CLOSE_DRAG_PX = 80

type Props = {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Read by screen readers only (the sheet's name). */
  title: string
  /** A button that opens the sheet; leave it out when the sheet is opened from elsewhere (cycle 10). */
  trigger?: ReactNode
  triggerLabel?: string
  triggerClassName?: string
  /** A ⓧ button at the top right (cycle 10, flow.md "폰 아래 창 공통"). The search settings sheet has none (C6). */
  closeButton?: boolean
  /** The phone's back button closes only the sheet (cycle 10 windows, `useBackToClose`). */
  closeOnBack?: boolean
  children: ReactNode
}

/**
 * Phones (cycle 08, flow.md 검토 #1; shared in cycle 10): a sheet from the bottom of the screen with a handle. A tap
 * outside, Escape or pulling the handle down closes it; optionally a ⓧ button and the back button too.
 */
export function BottomSheet({
  open,
  onOpenChange,
  title,
  trigger,
  triggerLabel,
  triggerClassName,
  closeButton = false,
  closeOnBack = false,
  children,
}: Props) {
  const [drag, setDrag] = useState<{ startY: number; offset: number } | null>(null)
  useBackToClose(open && closeOnBack, () => onOpenChange(false))

  const endDrag = () => {
    if (drag && drag.offset > CLOSE_DRAG_PX) onOpenChange(false)
    setDrag(null)
  }

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      {trigger !== undefined && (
        <Dialog.Trigger className={triggerClassName} aria-label={triggerLabel}>
          {trigger}
        </Dialog.Trigger>
      )}
      <Dialog.Portal>
        <Dialog.Overlay className={styles.sheetOverlay} />
        <Dialog.Content
          className={styles.bottomSheet}
          aria-describedby={undefined}
          data-close={closeButton || undefined}
          style={drag ? { transform: `translateY(${drag.offset}px)`, transition: 'none' } : undefined}
        >
          <Dialog.Title className="visually-hidden">{title}</Dialog.Title>
          <div
            className={styles.handleArea}
            data-testid="sheet-handle"
            onPointerDown={(event) => {
              event.currentTarget.setPointerCapture?.(event.pointerId)
              setDrag({ startY: event.clientY, offset: 0 })
            }}
            onPointerMove={(event) => {
              if (drag) setDrag({ ...drag, offset: Math.max(0, event.clientY - drag.startY) })
            }}
            onPointerUp={endDrag}
            onPointerCancel={() => setDrag(null)}
          >
            <span className={styles.handle} aria-hidden="true" />
          </div>
          {closeButton && (
            <Dialog.Close className={styles.close} aria-label="닫기">
              <span aria-hidden="true">×</span>
            </Dialog.Close>
          )}
          <div className={styles.sheetBody}>{children}</div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
