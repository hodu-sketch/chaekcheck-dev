import { useId, useState, type ReactNode } from 'react'
import { useAuth } from '../auth/AuthContext'
import { LoginDialog } from '../auth/LoginDialog'
import { WithdrawDialog } from '../auth/WithdrawDialog'
import styles from './AccountSection.module.css'

/**
 * 마이페이지's 계정 part (user 2026-10-19: 내 도서관 관리 and 계정 are one page): the login state, and after login
 * 탈퇴하기 far below everything else, which opens the S7 withdrawal dialog. No provider name on the page (C23); the
 * dialog keeps its one line (C33). Before login it asks to log in; with login switched off there is nothing to
 * manage. `beforeWithdraw` (the page's 돌아가기) goes between the two.
 */
export function AccountSection({ beforeWithdraw }: { beforeWithdraw?: ReactNode }) {
  const { state } = useAuth()
  const [loginOpen, setLoginOpen] = useState(false)
  const [withdrawOpen, setWithdrawOpen] = useState(false)
  const titleId = useId()

  if (state.status === 'checking') return <>{beforeWithdraw}</>

  const provider = state.status === 'signed-in' ? state.me.loginMethods[0] : undefined

  return (
    <>
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
          </div>
        )}
      </section>
      {beforeWithdraw}
      {/* at the very bottom, after a lot of room (user 2026-10-19: "아주 많이 아래로 내리면 탈퇴하기") */}
      {provider && (
        <div className={styles.withdrawRow} data-testid="withdraw-row">
          <button type="button" className={`${styles.button} ${styles.withdraw}`} onClick={() => setWithdrawOpen(true)}>
            탈퇴하기
          </button>
          <WithdrawDialog open={withdrawOpen} onOpenChange={setWithdrawOpen} provider={provider} />
        </div>
      )}
    </>
  )
}
