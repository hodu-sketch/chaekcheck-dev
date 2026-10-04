import { useState } from 'react'
import { useAuth } from './AuthContext'
import { LoginDialog } from './LoginDialog'
import styles from './AuthHeaderAction.module.css'

/**
 * The right end of the common header (S1, S3): "로그인" before login, "로그아웃" after. While the first check runs
 * the place stays empty (no flicker); with login switched off there is nothing. No provider name or nickname
 * (AC-5). Logout has no confirmation and stays on the screen (flow.md).
 */
export function AuthHeaderAction() {
  const { state, signOut } = useAuth()
  const [dialogOpen, setDialogOpen] = useState(false)
  const [busy, setBusy] = useState(false)

  if (state.status === 'off') return null
  if (state.status === 'checking') return <span className={styles.placeholder} aria-hidden="true" />

  if (state.status === 'signed-in') {
    const handleLogout = async () => {
      setBusy(true)
      try {
        await signOut()
      } finally {
        setBusy(false)
      }
    }
    return (
      <button type="button" className={styles.button} disabled={busy} onClick={handleLogout}>
        로그아웃
      </button>
    )
  }

  return (
    <>
      <button type="button" className={styles.button} onClick={() => setDialogOpen(true)}>
        로그인
      </button>
      <LoginDialog open={dialogOpen} onOpenChange={setDialogOpen} />
    </>
  )
}
