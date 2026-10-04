import { Dialog } from 'radix-ui'
import { useState } from 'react'
import { useLocation } from 'react-router'
import { loginStartUrl } from '../../api/auth'
import type { LoginProvider } from '../../api/types'
import { providerName, rememberReturnPath } from './authResult'
import styles from './WithdrawDialog.module.css'

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The provider the account signs in with (`GET /api/v1/me` `loginMethods`). */
  provider: LoginProvider
  /** Page navigation; replaced in tests. */
  navigate?: (url: string) => void
}

/**
 * S7: what withdrawal deletes, then the provider's re-authentication (flow.md). The provider's name appears only
 * here, in one line (C33). 계속 leaves the page for the provider; 그만두기 goes back to the settings.
 */
export function WithdrawDialog({ open, onOpenChange, provider, navigate = (url) => window.location.assign(url) }: Props) {
  const location = useLocation()
  const [leaving, setLeaving] = useState(false)

  const proceed = () => {
    // After withdrawal the browser comes back to this screen (dev choice, step 14)
    rememberReturnPath(location.pathname + location.search)
    setLeaving(true)
    navigate(loginStartUrl(provider, 'withdraw'))
  }

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className={styles.overlay} />
        <Dialog.Content className={styles.content}>
          <Dialog.Title className={styles.title}>탈퇴</Dialog.Title>
          <Dialog.Description asChild>
            <div className={styles.body}>
              <p>탈퇴하면 아래 정보가 지워져요.</p>
              <ul className={styles.list}>
                <li>계정</li>
                <li>가입 때 동의한 기록</li>
                <li>저장한 지역과 내 도서관</li>
              </ul>
              <p className={styles.warn}>되돌릴 수 없어요.</p>
              <p>{providerName(provider)}와 chaekcheck의 연결도 끊어요.</p>
              <p>가입한 계정으로 한 번 더 로그인해 확인합니다.</p>
            </div>
          </Dialog.Description>
          <div className={styles.actions}>
            <Dialog.Close asChild>
              <button type="button" className={styles.stay}>
                그만두기
              </button>
            </Dialog.Close>
            <button type="button" className={styles.go} disabled={leaving} onClick={proceed}>
              계속
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
