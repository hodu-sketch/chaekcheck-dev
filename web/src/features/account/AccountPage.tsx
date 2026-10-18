import { useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import { LoginDialog } from '../auth/LoginDialog'
import { WithdrawDialog } from '../auth/WithdrawDialog'
import styles from './AccountPage.module.css'

/**
 * 마이페이지 > 계정 (`/account`, user request 2026-10-18): one line with 탈퇴하기, which opens the S7 withdrawal dialog
 * as the search settings did before. No provider name on the page (C23); the dialog keeps its one line (C33).
 * Before login it asks to log in; with login switched off there is nothing to manage.
 */
export function AccountPage() {
  const { state } = useAuth()
  const [loginOpen, setLoginOpen] = useState(false)
  const [withdrawOpen, setWithdrawOpen] = useState(false)

  if (state.status === 'checking') return <p className={styles.page}>불러오는 중</p>

  const provider = state.status === 'signed-in' ? state.me.loginMethods[0] : undefined

  return (
    <section className={styles.page} aria-labelledby="account-title">
      <h1 id="account-title" className={styles.title}>
        계정
      </h1>
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
          {provider && (
            <>
              <button type="button" className={`${styles.button} ${styles.withdraw}`} onClick={() => setWithdrawOpen(true)}>
                탈퇴하기
              </button>
              <WithdrawDialog open={withdrawOpen} onOpenChange={setWithdrawOpen} provider={provider} />
            </>
          )}
        </div>
      )}
    </section>
  )
}
