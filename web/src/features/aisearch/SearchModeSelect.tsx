import { DropdownMenu } from 'radix-ui'
import { CheckMark, ChevronDown } from '../../components/icons'
import { AI_TEXT } from './aiSearchText'
import styles from './SearchModeSelect.module.css'

export type SearchMode = 'ai' | 'direct'

const LABELS: Record<SearchMode, string> = { ai: AI_TEXT.tabAi, direct: AI_TEXT.tabDirect }

type Props = {
  value: SearchMode
  onChange: (mode: SearchMode) => void
}

/**
 * The search box's left end on the first screen: [AI ▾] or [직접 ▾], a small menu of the two ways to search (user
 * 2026-10-24: "검색창 안에 좌측에 AI 직접 이렇게 하고 AI가 기본값으로 하고 셀렉트박스같은걸로 선택하게 해줘").
 */
export function SearchModeSelect({ value, onChange }: Props) {
  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger className={styles.trigger} aria-label={`찾는 방법: ${LABELS[value]}`}>
        {LABELS[value]}
        <ChevronDown className={styles.chevron} />
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          className={styles.content}
          side="bottom"
          align="start"
          sideOffset={8}
          collisionPadding={12}
          // the chosen way's own box takes the focus (the parent moves it), not this trigger
          onCloseAutoFocus={(event) => event.preventDefault()}
        >
          <DropdownMenu.RadioGroup value={value} onValueChange={(next) => onChange(next as SearchMode)}>
            {(['ai', 'direct'] as const).map((mode) => (
              <DropdownMenu.RadioItem key={mode} value={mode} className={styles.item}>
                {LABELS[mode]}
                <DropdownMenu.ItemIndicator className={styles.check} aria-hidden="true">
                  <CheckMark />
                </DropdownMenu.ItemIndicator>
              </DropdownMenu.RadioItem>
            ))}
          </DropdownMenu.RadioGroup>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  )
}
