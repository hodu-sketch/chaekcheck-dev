import { useId, useState } from 'react'
import { ChevronDown } from './icons'
import { Markdown } from './Markdown'
import styles from './ChangeSummary.module.css'

/**
 * "바뀐 점 보기 ▾" under a title (cycle 16, spec R3-3): one folded line that opens in place. Only for a version that has
 * a change summary; no yellow box (C8).
 */
export function ChangeSummary({ summary }: { summary: string | null | undefined }) {
  const [open, setOpen] = useState(false)
  const id = useId()
  if (!summary) return null
  return (
    <div className={styles.box}>
      <button
        type="button"
        className={styles.toggle}
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen(!open)}
      >
        바뀐 점 보기
        {/* the app's chevron, not the ▾ glyph: the same mark on every fold (user 2026-10-17, "화살표랑 꺽쇠 섞여있는데") */}
        <span className={styles.arrow} data-open={open} aria-hidden="true">
          <ChevronDown size={10} />
        </span>
      </button>
      {open && (
        <div id={id} className={styles.body}>
          <Markdown>{summary}</Markdown>
        </div>
      )}
    </div>
  )
}
