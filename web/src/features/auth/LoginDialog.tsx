import { useQuery } from '@tanstack/react-query'
import { Dialog } from 'radix-ui'
import { useState } from 'react'
import { useLocation } from 'react-router'
import { fetchProviders, loginStartUrl } from '../../api/auth'
import type { LoginProvider } from '../../api/types'
import kakaoSymbol from '../../assets/login/kakao-symbol.svg'
import naverLogin from '../../assets/login/naver-login.png'
import { BottomSheet } from '../../components/BottomSheet'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { rememberReturnPath } from './authResult'
import styles from './LoginDialog.module.css'

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Page navigation; replaced in tests. */
  navigate?: (url: string) => void
}

/**
 * S5: choose a provider (flow.md). The buttons follow the providers' guides (plan research.md 4절): Naver's official
 * button image, Kakao's official symbol on #FEE500 with "카카오 로그인", same size, one under the other. Only the
 * providers the server offers are shown (AC-22, `GET /api/v1/auth/providers`). Phones: the shared bottom sheet with a
 * handle and ⓧ like the other phone sheets, and back closes only the sheet; no separate 닫기 button there (user
 * 2026-10-12: "로그인 창도 이 다섯 개처럼 손잡이와 ⓧ를 붙여줘").
 */
export function LoginDialog({ open, onOpenChange, navigate = (url) => window.location.assign(url) }: Props) {
  const location = useLocation()
  const providers = useQuery({ queryKey: ['auth', 'providers'], queryFn: fetchProviders, enabled: open, staleTime: Infinity })
  const [leaving, setLeaving] = useState(false)
  const phone = useMediaQuery('(width <= 640px)')

  const start = (provider: LoginProvider) => {
    rememberReturnPath(location.pathname + location.search)
    setLeaving(true)
    navigate(loginStartUrl(provider, 'login'))
  }

  const buttons = (
    <div className={styles.buttons}>
      {providers.data?.includes('NAVER') && (
        <button type="button" className={styles.naver} disabled={leaving} onClick={() => start('NAVER')}>
          <img src={naverLogin} alt="네이버 로그인" className={styles.naverImage} />
        </button>
      )}
      {providers.data?.includes('KAKAO') && (
        <button type="button" className={styles.kakao} disabled={leaving} onClick={() => start('KAKAO')}>
          <img src={kakaoSymbol} alt="" className={styles.kakaoSymbol} />
          <span className={styles.kakaoLabel}>카카오 로그인</span>
        </button>
      )}
      {providers.isError && <p className={styles.error}>지금은 로그인할 수 없어요. 잠시 뒤 다시 해 주세요</p>}
    </div>
  )

  if (phone) {
    return (
      <BottomSheet open={open} onOpenChange={onOpenChange} title="로그인" closeButton closeOnBack>
        <div className={styles.sheet}>
          <p className={styles.title} aria-hidden="true">
            로그인
          </p>
          <p className={styles.description}>처음이면 회원가입을 먼저 진행해요</p>
          {buttons}
        </div>
      </BottomSheet>
    )
  }
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className={styles.overlay} />
        <Dialog.Content className={styles.content}>
          <Dialog.Title className={styles.title}>로그인</Dialog.Title>
          <Dialog.Description className={styles.description}>처음이면 회원가입을 먼저 진행해요</Dialog.Description>
          {buttons}
          <Dialog.Close asChild>
            <button type="button" className={styles.close}>
              닫기
            </button>
          </Dialog.Close>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
