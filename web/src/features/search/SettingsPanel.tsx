import { Popover, RadioGroup } from 'radix-ui'
import { useState } from 'react'
import { useOptionalAuth } from '../auth/AuthContext'
import { WithdrawDialog } from '../auth/WithdrawDialog'
import { DIRECT_REASON, type SearchSettings, type ViewMode } from './searchSettings'
import styles from './SettingsPanel.module.css'

type Props = {
  settings: SearchSettings
  /** Applied at once; with results the search runs again [안]. */
  onChange: (settings: SearchSettings) => void
}

/**
 * S4 settings (flow.md): 보기 방식 and 도서관에서 직접 찾기, in a small window under ⚙. Kept in this browser and in the
 * address [확정]. With direct search on, 책 먼저 고르기 cannot be used [안]: site results are not grouped by book.
 * Signed in, a "계정" section holds only 탈퇴 (cycle 05 [확정]); login and logout stay in the header.
 */
export function SettingsPanel({ settings, onChange }: Props) {
  const auth = useOptionalAuth()
  const [open, setOpen] = useState(false)
  const [withdrawOpen, setWithdrawOpen] = useState(false)
  const signedIn = auth?.state.status === 'signed-in' ? auth.state : null
  const provider = signedIn?.me.loginMethods[0]

  return (
    <>
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger className={styles.trigger} aria-label="검색 설정">
        <span aria-hidden="true">⚙</span>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content className={styles.content} side="bottom" align="end" sideOffset={8} collisionPadding={12}>
          <fieldset className={styles.group}>
            <legend className={styles.legend}>보기 방식</legend>
            <RadioGroup.Root
              className={styles.options}
              value={settings.view}
              onValueChange={(value) => onChange({ ...settings, view: value as ViewMode })}
              aria-label="보기 방식"
            >
              <Option value="instant" label="바로 보기" hint="찾은 책의 대출·예약을 바로 보여 줘요" />
              <Option
                value="books"
                label="책 먼저 고르기"
                hint={settings.direct ? DIRECT_REASON : '책을 고른 뒤 도서관별로 보여 줘요'}
                disabled={settings.direct}
              />
            </RadioGroup.Root>
          </fieldset>
          <fieldset className={styles.group}>
            <legend className={styles.legend}>도서관에서 직접 찾기</legend>
            <RadioGroup.Root
              className={styles.options}
              value={settings.direct ? 'on' : 'off'}
              onValueChange={(value) => onChange({ ...settings, direct: value === 'on' })}
              aria-label="도서관에서 직접 찾기"
            >
              <Option value="off" label="끔" hint="수집한 책 목록에서 찾아요" />
              <Option value="on" label="켬" hint="도서관 사이트에서 제목 하나로 찾아요" />
            </RadioGroup.Root>
          </fieldset>
          {provider && (
            <fieldset className={styles.group}>
              <legend className={styles.legend}>계정</legend>
              <button
                type="button"
                className={styles.withdraw}
                onClick={() => {
                  setOpen(false)
                  setWithdrawOpen(true)
                }}
              >
                탈퇴
              </button>
            </fieldset>
          )}
          <Popover.Arrow className={styles.arrow} width={12} height={6} />
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
    {provider && (
      <WithdrawDialog
        open={withdrawOpen}
        onOpenChange={(next) => {
          setWithdrawOpen(next)
          // 그만두기 goes back to the settings (S4)
          if (!next) setOpen(true)
        }}
        provider={provider}
      />
    )}
    </>
  )
}

function Option({ value, label, hint, disabled }: { value: string; label: string; hint: string; disabled?: boolean }) {
  return (
    <label className={styles.option} data-disabled={disabled || undefined}>
      <RadioGroup.Item value={value} className={styles.radio} disabled={disabled}>
        <RadioGroup.Indicator className={styles.dot} />
      </RadioGroup.Item>
      <span>
        <span className={styles.optionLabel}>{label}</span>
        <span className={styles.hint}>{hint}</span>
      </span>
    </label>
  )
}
