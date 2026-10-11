import { Popover } from 'radix-ui'
import { useRef, useState } from 'react'
import styles from './InfoTip.module.css'

/**
 * A small ⓘ next to a title (cycle 08, flow.md 검토 #7): the text shows while the mouse is on it, and on a tap on
 * touch screens (a tooltip alone would never open there). A mouse click keeps it open instead of closing what the
 * hover opened.
 */
export function InfoTip({ label, text }: { label: string; text: string }) {
  const [open, setOpen] = useState(false)
  const pointer = useRef('')
  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger
        className={styles.trigger}
        aria-label={label}
        onPointerDown={(event) => {
          pointer.current = event.pointerType
        }}
        onPointerEnter={(event) => event.pointerType === 'mouse' && setOpen(true)}
        onPointerLeave={(event) => event.pointerType === 'mouse' && setOpen(false)}
        onClick={(event) => {
          if (open && pointer.current === 'mouse') event.preventDefault()
        }}
      >
        ⓘ
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content className={styles.content} side="bottom" align="start" sideOffset={6} collisionPadding={12}>
          {text}
          <Popover.Arrow className={styles.arrow} width={10} height={5} />
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}
