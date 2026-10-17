import { Popover } from 'radix-ui'
import { useEffect, useState } from 'react'
import { useHeaderOpen } from '../../components/HeaderOpen'
import { LEVEL_ARIA, LEVEL_LABEL, secondLine } from './lookupStatusText'
import { useLookupStatus } from './LookupStatusProvider'
import styles from './HeaderSignal.module.css'

/**
 * The lookup status light in the header, left of 로그인 (cycle 20 spec R1~R5, R14, ADR-0118, 0121; mock-up approved
 * 2026-10-17): a dot with a short text on wide screens, the dot alone on phones (640px and below, 44px tap area).
 * Nothing is drawn before the first answer. A click on the dot or the text opens the same popover on every width
 * (no hover, no title tooltip [확정], review #20): the label, then the second line by color and reason. No motion.
 * Accessibility: the button is named "조회 상태: 원활" by color; a polite `role="status"` text beside it changes only
 * when the level changes, so a same answer is not read again (AC-20).
 */
export function HeaderSignal() {
  const { view } = useLookupStatus()
  const { owner, claim, release } = useHeaderOpen()
  const [open, setOpen] = useState(false)
  // only the level, not the reason: the live text changes when the color does and no more often
  const level = view?.level ?? null

  // the menus opened: this closes (handoff 1-12 [확정])
  useEffect(() => {
    if (open && owner !== 'status') setOpen(false)
  }, [owner, open])

  const change = (next: boolean) => {
    setOpen(next)
    if (next) claim('status')
    else release('status')
  }

  if (!view || !level) return null
  return (
    <>
      <Popover.Root open={open} onOpenChange={change}>
        <Popover.Trigger className={styles.button} data-level={level} aria-label={LEVEL_ARIA[level]}>
          <span className={styles.dot} aria-hidden="true" />
          <span className={styles.label} aria-hidden="true">
            {LEVEL_LABEL[level]}
          </span>
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Content className={styles.popover} side="bottom" align="end" sideOffset={8} collisionPadding={12}>
            <p className={styles.popoverTitle} data-level={level}>
              <span className={styles.dot} aria-hidden="true" />
              {LEVEL_LABEL[level]}
            </p>
            <p className={styles.popoverLine}>{secondLine(view)}</p>
            <Popover.Arrow className={styles.arrow} width={12} height={6} />
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
      <span className="visually-hidden" role="status" aria-live="polite">
        {LEVEL_LABEL[level]}
      </span>
    </>
  )
}
