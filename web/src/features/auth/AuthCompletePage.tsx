import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { deleteMe } from '../../api/auth'
import { useAuth } from './AuthContext'
import { readAuthResult, takeReturnPath, withdrawFailureMessage } from './authResult'
import { useNotice } from './NoticeContext'
import styles from './AuthCompletePage.module.css'

/**
 * `/auth/complete?result=…`: where the server sends the browser after a provider (ADR-0045). The code decides where
 * to go and is then gone from the address (replace).
 * <ul>
 * <li>`login_success`: back to the address remembered before leaving (only a path inside this site, C21)</li>
 * <li>`withdraw_reauthenticated`: "탈퇴 처리 중", `DELETE /api/v1/me`, then "탈퇴했습니다" signed out, or the reason
 * the withdrawal stopped (still signed in, flow.md S7); back to where the withdrawal started</li>
 * <li>a failure code: the fixed notice on S1; an unknown code: S1 without a notice</li>
 * </ul>
 */
export function AuthCompletePage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const { showNotice } = useNotice()
  const { markSignedOut } = useAuth()
  const [withdrawing, setWithdrawing] = useState(false)
  const handled = useRef(false)

  useEffect(() => {
    // StrictMode runs effects twice in development; the remembered address must be taken once
    if (handled.current) return
    handled.current = true
    const result = readAuthResult(params.get('result'), params.get('provider'))
    const returnTo = takeReturnPath()
    switch (result.kind) {
      case 'signed-in':
        navigate(returnTo, { replace: true })
        break
      case 'notice':
        showNotice(result.message)
        navigate('/', { replace: true })
        break
      case 'withdraw':
        setWithdrawing(true)
        deleteMe()
          .then(() => {
            markSignedOut()
            showNotice('탈퇴했습니다')
          })
          .catch((error: unknown) => showNotice(withdrawFailureMessage(error)))
          .finally(() => navigate(returnTo, { replace: true }))
        break
      case 'ignore':
        navigate('/', { replace: true })
        break
    }
  }, [params, navigate, showNotice, markSignedOut])

  return withdrawing ? (
    <p className={styles.working} role="status">
      탈퇴 처리 중
    </p>
  ) : null
}
