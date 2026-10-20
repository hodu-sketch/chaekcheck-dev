import { useId, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { LoginDialog } from '../auth/LoginDialog'
import { WithdrawDialog } from '../auth/WithdrawDialog'
import styles from './AccountSection.module.css'

/**
 * 마이페이지's 계정 part (user 2026-10-19: 내 도서관 관리 and 계정 are one page): before login it asks to log in; after
 * login "로그인되어 있어요 [로그아웃]" (the header's signOut) and, at the bottom of this part, a small grey 「탈퇴하기」 link
 * that opens the S7 withdrawal dialog (user 2026-10-20, ADR-0139 #9, handoff 5-A-8: no empty room and no button far
 * below any more). No provider name on the page (C23); with login switched off there is nothing to manage.
 */
export function AccountSection() {
  const { state, signOut } = useAuth()
  const [loginOpen, setLoginOpen] = useState(false)
  const [withdrawOpen, setWithdrawOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const titleId = useId()

  if (state.status === 'checking') return null

  const provider = state.status === 'signed-in' ? state.me.loginMethods[0] : undefined
  const handleLogout = async () => {
    setBusy(true)
    try {
      await signOut()
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className={styles.section} aria-labelledby={titleId}>
      <h2 id={titleId} className={styles.title}>
        계정
      </h2>
      {state.status === 'off' ? (
        <p className={styles.muted}>지금은 로그인을 쓸 수 없어요</p>
      ) : state.status === 'signed-out' ? (
        <div className={styles.row}>
          <span className={styles.muted}>로그인하면 계정을 관리할 수 있어요</span>
          <button type="button" className={styles.button} onClick={() => setLoginOpen(true)}>
            로그인
          </button>
          <LoginDialog open={loginOpen} onOpenChange={setLoginOpen} />
        </div>
      ) : (
        <div className={styles.row}>
          <span>로그인되어 있어요</span>
          <button type="button" className={styles.button} disabled={busy} onClick={handleLogout}>
            로그아웃
          </button>
        </div>
      )}
      {provider && (
        <div className={styles.withdrawRow} data-testid="withdraw-row">
          <button type="button" className={styles.withdraw} onClick={() => setWithdrawOpen(true)}>
            탈퇴하기
          </button>
          <WithdrawDialog open={withdrawOpen} onOpenChange={setWithdrawOpen} provider={provider} />
        </div>
      )}
    </section>
  )
}
