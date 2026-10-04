import { useEffect, useRef } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { readAuthResult, takeReturnPath } from './authResult'
import { useNotice } from './NoticeContext'

/**
 * `/auth/complete?result=…`: where the server sends the browser after a provider (ADR-0045). Nothing is drawn; the
 * code decides where to go and is then gone from the address (replace).
 * <ul>
 * <li>`login_success`: back to the address remembered before leaving (only a path inside this site, C21)</li>
 * <li>a failure code: the fixed notice on S1</li>
 * <li>an unknown code: S1 without a notice</li>
 * </ul>
 */
export function AuthCompletePage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const { showNotice } = useNotice()
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
        // TODO(step 14): call DELETE /api/v1/me and show "탈퇴했습니다"
        navigate('/', { replace: true })
        break
      case 'ignore':
        navigate('/', { replace: true })
        break
    }
  }, [params, navigate, showNotice])

  return null
}
