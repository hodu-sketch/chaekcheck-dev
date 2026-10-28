import { Popover } from 'radix-ui'
import styles from './PicksDialogs.module.css'

/** What the ⓘ next to 「AI 요약」 says (user 2026-10-19: "짧게 ai가 요약한거라고 … i를 누른다던가"; no model name). */
export const AI_SUMMARY_NOTE = 'AI가 책 정보를 바탕으로 쓴 짧은 요약이에요. 실제 내용과 다를 수 있어요.'

/**
 * 안 B (user 2026-10-19): a small violet 「AI 요약 ⓘ」 label, then the summary in darker words than the grey line under
 * it. The ⓘ opens a short note. Shared by the book window (S14) and, since cycle 26, the book page (flow S2), so the
 * same book reads the same everywhere (AC-1); the text is never cut.
 */
export function AiSummary({ text, className }: { text: string; className?: string }) {
  return (
    <div className={className ? `${styles.aiSummary} ${className}` : styles.aiSummary}>
      <Popover.Root>
        <Popover.Trigger className={styles.aiLabel} aria-label="AI 요약 안내">
          AI 요약
          <span className={styles.aiInfo} aria-hidden="true">
            i
          </span>
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Content className={styles.aiNote} side="top" align="start" sideOffset={6} collisionPadding={12}>
            {AI_SUMMARY_NOTE}
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
      <p className={styles.aiText}>{text}</p>
    </div>
  )
}
